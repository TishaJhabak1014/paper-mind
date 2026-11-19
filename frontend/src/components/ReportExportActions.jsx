import React, { useMemo, useState } from 'react';
import { Alert, Box, IconButton, Link, Tooltip, Typography } from '@mui/material';
import FileCopyIcon from '@mui/icons-material/FileCopy';
import SaveIcon from '@mui/icons-material/Save';
import { Document, ExternalHyperlink, HeadingLevel, Packer, Paragraph, TextRun } from 'docx';

const DRIVE_SCOPE = 'https://www.googleapis.com/auth/drive.file';
const DOCX_MIME = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';

const verifiedUrl = value => {
  try {
    const url = new URL(value);
    return ['http:', 'https:'].includes(url.protocol) ? url.href : null;
  } catch { return null; }
};

const authorLabel = (authors, year, fallback) => {
  const names = (authors || []).filter(Boolean);
  const author = names.length > 1 ? `${names[0]} et al.` : names[0] || fallback || 'Source';
  return `${author}${year ? `, ${year}` : ''}`;
};

export function getReportReferences(report) {
  const refs = new Map();
  (report?.sources || []).forEach(source => {
    refs.set(`SOURCE:${source.paperId}`, {
      label: authorLabel(source.authors, source.year, source.title), url: verifiedUrl(source.sourceUrl), title: source.title,
    });
    (source.citationPassages || []).forEach(passage => refs.set(`CONTEXT:${passage.contextId}`, {
      label: authorLabel(passage.citingPaperAuthors, passage.citingPaperYear, passage.citingPaperTitle),
      url: verifiedUrl(passage.citingPaperUrl), title: passage.citingPaperTitle,
    }));
  });
  return refs;
}

function markerParts(text, refs) {
  const parts = [];
  const regex = /\[\[(SOURCE|CONTEXT):([^\]]+)\]\]/g;
  let last = 0; let match;
  while ((match = regex.exec(text || ''))) {
    if (match.index > last) parts.push({ text: text.slice(last, match.index) });
    const ref = refs.get(`${match[1]}:${match[2]}`);
    if (ref) parts.push({ ...ref, text: `[${ref.label}]` });
    last = regex.lastIndex;
  }
  if (last < (text || '').length) parts.push({ text: text.slice(last) });
  return parts;
}

function InlineReferences({ text, refs }) {
  return <>{markerParts(text, refs).map((part, i) => part.label && part.url
    ? <Link key={i} href={part.url} target="_blank" rel="noreferrer">{part.text}</Link>
    : <React.Fragment key={i}>{part.text}</React.Fragment>)}</>;
}

function markdownText(text, refs) {
  return markerParts(text, refs).map(part => part.label && part.url
    ? `[${part.label}](${part.url})` : part.text || (part.label ? `[${part.label}]` : '')).join('');
}

function docxRuns(text, refs) {
  return markerParts(text, refs).flatMap(part => part.label && part.url
    ? [new ExternalHyperlink({ link: part.url, children: [new TextRun({ text: `[${part.label}]`, style: 'Hyperlink' })] })]
    : part.text ? [new TextRun(part.text)] : []);
}

export default function ReportExportActions({ report }) {
  const [message, setMessage] = useState('');
  const [saving, setSaving] = useState(false);
  const [driveUrl, setDriveUrl] = useState('');
  const refs = useMemo(() => getReportReferences(report), [report]);
  const linkedReferences = [...new Map([...refs.values()].filter(ref => ref.url).map(ref => [ref.url, ref])).values()];

  const reportLines = () => {
    const lines = [
      '# Research report',
      report.evidenceBasis ? `Evidence basis: ${report.evidenceBasis}` : '',
      report.coverageDescription || '',
      '## Synthesis', markdownText(report.synthesis, refs),
    ];
    if (report.claims?.length) {
      lines.push('## Evidence claims');
      report.claims.forEach(claim => {
        const ids = claim.sourcePaperIds || [];
        const citations = ids.map(id => refs.get(`SOURCE:${id}`)).filter(Boolean);
        lines.push(`${claim.text}${citations.length ? ` ${citations.map(ref => ref.url ? `[${ref.label}](${ref.url})` : `[${ref.label}]`).join(' ')}` : ''}`);
        if (claim.caveat) lines.push(claim.caveat);
      });
    }
    if (report.citationAnalyses?.length) {
      lines.push('## Citation context');
      report.citationAnalyses.forEach(analysis => {
        const ref = refs.get(`CONTEXT:${analysis.contextId}`);
        lines.push(`**${analysis.relation}** — ${analysis.explanation}${ref ? ` ${ref.url ? `[${ref.label}](${ref.url})` : `[${ref.label}]`}` : ''}`);
        const sourcePassage = (report.sources || []).flatMap(source => source.citationPassages || []).find(item => item.contextId === analysis.contextId);
        if (sourcePassage) lines.push(`Cites ${sourcePassage.focalTitle}: “${sourcePassage.passage}”`);
      });
    }
    if (report.coverageGaps?.length) lines.push(`## Coverage gaps\n${report.coverageGaps.join('\n')}`);
    if (report.limitations?.length) lines.push(`## Limitations\n${report.limitations.join('\n')}`);
    if (linkedReferences.length) lines.push(`## References\n${linkedReferences.map(ref => `[${ref.label}](${ref.url})`).join('\n')}`);
    return lines.filter(Boolean);
  };

  const copy = async () => {
    try { await navigator.clipboard.writeText(reportLines().join('\n\n')); setMessage('Report copied with references.'); }
    catch { setMessage('Could not copy the report. Check browser clipboard permissions.'); }
  };

  const saveToDrive = async () => {
    const clientId = import.meta.env.VITE_GOOGLE_CLIENT_ID;
    setMessage(''); setDriveUrl('');
    if (!clientId) { setMessage('Google Drive is not configured. Set VITE_GOOGLE_CLIENT_ID and rebuild the frontend.'); return; }
    setSaving(true);
    try {
      const accessToken = await new Promise((resolve, reject) => {
        const oauth = window.google?.accounts?.oauth2;
        if (!oauth) return reject(new Error('Google sign-in did not load. Refresh the page and try again.'));
        oauth.initTokenClient({ client_id: clientId, scope: DRIVE_SCOPE, callback: response => response.error || !response.access_token
          ? reject(new Error(response.error_description || response.error || 'Google sign-in did not return an access token.'))
          : resolve(response.access_token), error_callback: error => reject(new Error(error?.message || 'Google sign-in was blocked.'))
        }).requestAccessToken({ prompt: 'consent' });
      });
      const children = [
        new Paragraph({ text: 'Research report', heading: HeadingLevel.HEADING_1 }),
        ...(report.evidenceBasis ? [new Paragraph(report.evidenceBasis)] : []),
        ...(report.coverageDescription ? [new Paragraph(report.coverageDescription)] : []),
        new Paragraph({ text: 'Synthesis', heading: HeadingLevel.HEADING_2 }),
        new Paragraph({ children: docxRuns(report.synthesis, refs) }),
      ];
      if (report.claims?.length) {
        children.push(new Paragraph({ text: 'Evidence claims', heading: HeadingLevel.HEADING_2 }));
        report.claims.forEach(claim => {
          children.push(new Paragraph({ children: [...docxRuns(claim.text, refs), ...((claim.sourcePaperIds || []).flatMap(id => {
            const ref = refs.get(`SOURCE:${id}`); return ref ? [new TextRun(' '), ...(ref.url ? [new ExternalHyperlink({ link: ref.url, children: [new TextRun({ text: `[${ref.label}]`, style: 'Hyperlink' })] })] : [new TextRun(`[${ref.label}]`)])] : [];
          }))] }));
          if (claim.caveat) children.push(new Paragraph({ children: [new TextRun({ text: claim.caveat, color: '666666' })] }));
        });
      }
      if (report.citationAnalyses?.length) {
        children.push(new Paragraph({ text: 'Citation context', heading: HeadingLevel.HEADING_2 }));
        report.citationAnalyses.forEach(analysis => {
          const ref = refs.get(`CONTEXT:${analysis.contextId}`);
          children.push(new Paragraph({ children: [new TextRun({ text: `${analysis.relation}: ${analysis.explanation} ` }), ...(ref?.url ? [new ExternalHyperlink({ link: ref.url, children: [new TextRun({ text: `[${ref.label}]`, style: 'Hyperlink' })] })] : ref ? [new TextRun(`[${ref.label}]`)] : [])] }));
          const passage = (report.sources || []).flatMap(source => source.citationPassages || []).find(item => item.contextId === analysis.contextId);
          if (passage) children.push(new Paragraph({ children: [new TextRun({ text: `Cites ${passage.focalTitle}: “${passage.passage}”`, color: '666666' })] }));
        });
      }
      if (report.coverageGaps?.length) children.push(new Paragraph({ children: [new TextRun({ text: `Coverage gaps: ${report.coverageGaps.join(' ')}` })] }));
      if (report.limitations?.length) children.push(new Paragraph({ children: [new TextRun({ text: `Limitations: ${report.limitations.join(' ')}` })] }));
      if (linkedReferences.length) {
        children.push(new Paragraph({ text: 'References', heading: HeadingLevel.HEADING_2 }));
        linkedReferences.forEach(ref => children.push(new Paragraph({ children: [new ExternalHyperlink({ link: ref.url, children: [new TextRun({ text: ref.label, style: 'Hyperlink' })] })] })));
      }
      const file = await Packer.toBlob(new Document({ sections: [{ children }] }));
      const body = new FormData();
      body.append('metadata', new Blob([JSON.stringify({ name: 'PaperMind research report.docx', mimeType: DOCX_MIME })], { type: 'application/json' }));
      body.append('file', file, 'papermind-report.docx');
      const response = await fetch('https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id', { method: 'POST', headers: { Authorization: `Bearer ${accessToken}` }, body });
      const result = await response.json();
      if (!response.ok || !result.id) throw new Error(result.error?.message || `Google Drive upload failed (${response.status}).`);
      setDriveUrl(`https://drive.google.com/file/d/${result.id}/view`); setMessage('Saved to your Google Drive.');
    } catch (error) { setMessage(error.message || 'Could not save this report to Google Drive.'); }
    finally { setSaving(false); }
  };

  const errorMessage = /could not|failed|blocked/i.test(message);
  const severity = driveUrl ? 'success' : errorMessage ? 'error' : 'info';

  return <Box sx={{ mr: 'auto', minWidth: 0 }}>
    <Tooltip title="Copy report">
      <IconButton onClick={copy} aria-label="Copy report" sx={{ color: '#4287f5' }}>
        <FileCopyIcon />
      </IconButton>
    </Tooltip>
    <Tooltip title="Save to Google Drive">
      <span>
        <IconButton onClick={saveToDrive} disabled={saving} aria-label="Save to Google Drive" sx={{ color: '#4287f5' }}>
          <SaveIcon />
        </IconButton>
      </span>
    </Tooltip>
    {saving && <Typography variant="caption" role="status" sx={{ display: 'block' }}>Saving to Google Drive…</Typography>}
    {message && <Alert severity={severity} sx={{ mt: 1 }}>
      {message}{' '}{driveUrl && <Link href={driveUrl} target="_blank" rel="noopener noreferrer">Open file</Link>}
    </Alert>}
  </Box>;
}

export { InlineReferences };
