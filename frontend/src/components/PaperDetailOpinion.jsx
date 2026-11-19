import React, { useState } from 'react';
import { Alert, Card, CardContent, IconButton, Link, Tooltip, Typography } from '@mui/material';
import FileCopyIcon from '@mui/icons-material/FileCopy';
import SaveIcon from '@mui/icons-material/Save';
import { Document, ExternalHyperlink, HeadingLevel, Packer, Paragraph, TextRun } from 'docx';

const DRIVE_SCOPE = 'https://www.googleapis.com/auth/drive.file';
const DRIVE_FILE_MIME_TYPE = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';

export function buildSummaryCopyText({ title, authorYear, summary, includeAbstractNote, references }) {
  return [
    title,
    includeAbstractNote ? 'This summary is based only on the available abstract, not the full paper.' : null,
    authorYear,
    summary,
    references.length ? `References\n${references.map(reference => `${reference.label}: ${reference.url}`).join('\n')}` : null,
  ].filter(Boolean).join('\n\n');
}

function PaperDetailOpinion({ paper }) {
  const summaryLabel = paper.summaryLabel || (paper.tldr?.text && !paper.abstract?.trim()
    ? 'Semantic Scholar TLDR'
    : 'Summary of available abstract');
  const authors = Array.isArray(paper.authors) ? paper.authors.map(author => author?.name).filter(Boolean) : [];
  const year = paper.publicationDate
    ? new Date(paper.publicationDate).getFullYear()
    : paper.year || null;
  const landingPageUrl = paper.url || paper.landingPageUrl || null;
  const pdfUrl = paper.openAccessPdf?.url || paper.pdfUrl || null;
  const authorYear = `${authors.length ? authors.join(', ') : 'Authors not supplied'}${year ? ` (${year})` : ' (year not supplied)'}`;
  const references = [
    landingPageUrl && { label: 'Paper landing page', url: landingPageUrl },
    pdfUrl && { label: 'Open-access PDF', url: pdfUrl },
  ].filter(Boolean);
  const includeAbstractNote = summaryLabel !== 'Semantic Scholar TLDR';
  const copyText = buildSummaryCopyText({
    title: paper.title || 'Untitled paper',
    authorYear,
    summary: paper.summary || '',
    includeAbstractNote,
    references,
  });
  const clientId = import.meta.env.VITE_GOOGLE_CLIENT_ID;
  const [saving, setSaving] = useState(false);
  const [driveMessage, setDriveMessage] = useState('');
  const [driveFileUrl, setDriveFileUrl] = useState('');

  const createDocx = async () => {
    const doc = new Document({
      sections: [{
        properties: {},
        children: [
          new Paragraph({ text: paper.title || 'Untitled paper', heading: HeadingLevel.HEADING_1 }),
          ...(includeAbstractNote ? [new Paragraph({ children: [new TextRun({ text: 'This summary is based only on the available abstract, not the full paper.', italics: true, size: 20 })] })] : []),
          new Paragraph({ children: [new TextRun({ text: authorYear, size: 20 })] }),
          new Paragraph({ children: [new TextRun({ text: paper.summary || '', size: 22 })] }),
          ...(references.length ? [
            new Paragraph({ text: 'References', heading: HeadingLevel.HEADING_2 }),
            ...references.map(reference => new Paragraph({
              children: [new ExternalHyperlink({
                link: reference.url,
                children: [new TextRun({ text: reference.label, style: 'Hyperlink' })],
              })],
            })),
          ] : []),
        ],
      }],
    });
    return Packer.toBlob(doc);
  };

  const getAccessToken = () => new Promise((resolve, reject) => {
    const oauth = window.google?.accounts?.oauth2;
    if (!oauth) {
      reject(new Error('Google sign-in did not load. Refresh the page and try again.'));
      return;
    }
    const tokenClient = oauth.initTokenClient({
      client_id: clientId,
      scope: DRIVE_SCOPE,
      callback: (response) => {
        if (response.error || !response.access_token) {
          reject(new Error(response.error_description || response.error || 'Google sign-in did not return an access token.'));
        } else {
          resolve(response.access_token);
        }
      },
      error_callback: (error) => reject(new Error(error?.message || 'Google sign-in was blocked.')),
    });
    tokenClient.requestAccessToken({ prompt: 'consent' });
  });

  const saveToDrive = async () => {
    setDriveMessage('');
    setDriveFileUrl('');
    if (!clientId) {
      setDriveMessage('Google Drive is not configured. Set VITE_GOOGLE_CLIENT_ID and rebuild the frontend.');
      return;
    }

    setSaving(true);
    try {
      const accessToken = await getAccessToken();
      const file = await createDocx();
      const metadata = {
        name: `${paper.title || 'PaperMind summary'}.docx`,
        mimeType: DRIVE_FILE_MIME_TYPE,
      };
      const body = new FormData();
      body.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }));
      body.append('file', file, 'summary.docx');

      const upload = await fetch('https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id', {
        method: 'POST',
        headers: { Authorization: `Bearer ${accessToken}` },
        body,
      });
      const result = await upload.json();
      if (!upload.ok || !result.id) {
        throw new Error(result.error?.message || `Google Drive upload failed (${upload.status}).`);
      }

      setDriveFileUrl(`https://drive.google.com/file/d/${result.id}/view`);
      setDriveMessage('Saved to your Google Drive.');
    } catch (error) {
      console.error('Google Drive save failed:', error);
      setDriveMessage(error.message || 'Could not save this summary to Google Drive.');
    } finally {
      setSaving(false);
    }
  };

  const copyToClipboard = async () => {
    try {
      await navigator.clipboard.writeText(copyText);
      setDriveMessage('Summary and references copied to clipboard.');
    } catch {
      setDriveMessage('Could not copy the summary. Check browser clipboard permissions.');
    }
  };

  return (
    <Card>
      <CardContent>
        <Typography variant="h6" component="h2">{paper.title || 'Untitled paper'}</Typography>
        {includeAbstractNote && (
          <Typography variant="caption" color="text.secondary" component="p">
            This summary is based only on the available abstract, not the full paper.
          </Typography>
        )}
        <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>{authorYear}</Typography>
        <Typography variant="body2">{paper.summary}</Typography>
        <Tooltip title="Copy summary">
          <IconButton onClick={copyToClipboard} aria-label="Copy summary" sx={{ color: '#4287f5' }}>
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
        {saving && <Typography variant="caption" role="status">Saving to Google Drive…</Typography>}
        {driveMessage && (
          <Alert severity={driveFileUrl ? 'success' : driveMessage.includes('Could not') || driveMessage.includes('failed') || driveMessage.includes('blocked') ? 'error' : 'info'} sx={{ mt: 1 }}>
            {driveMessage}{' '}
            {driveFileUrl && <Link href={driveFileUrl} target="_blank" rel="noopener noreferrer">Open file</Link>}
          </Alert>
        )}
      </CardContent>
    </Card>
  );
}

export default PaperDetailOpinion;
