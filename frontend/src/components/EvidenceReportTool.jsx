import React, { useEffect, useMemo, useState } from 'react';
import { Alert, Box, Button, Checkbox, CircularProgress, Dialog, DialogActions, DialogContent, DialogTitle, FormControl, FormControlLabel, InputLabel, MenuItem, Select, Typography } from '@mui/material';
import { api } from '../api';

const REPORT_TYPES = [
  { value: 'EVIDENCE_SYNTHESIS', label: 'Evidence synthesis' },
  { value: 'CITATION_CONTEXT', label: 'Citation-context report' },
];
const EMPTY_PAPER_IDS = [];

export default function EvidenceReportTool({ graphId, onOpen, preset = null, presetPaperIds = EMPTY_PAPER_IDS, buttonLabel = 'Create evidence report' }) {
  const [open, setOpen] = useState(false);
  const [type, setType] = useState(preset?.reportType || 'EVIDENCE_SYNTHESIS');
  const [scope, setScope] = useState(preset?.scope || 'ALL');
  const [preview, setPreview] = useState(null);
  const [included, setIncluded] = useState([]);
  const [report, setReport] = useState(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const presetIdsKey = preset ? JSON.stringify(presetPaperIds) : '';
  const request = useMemo(() => ({ graphId, reportType: type, scope, selectedPaperIds: preset ? JSON.parse(presetIdsKey) : null }),
    [graphId, type, scope, Boolean(preset), preset?.reportType, preset?.scope, presetIdsKey]);

  useEffect(() => {
    if (!open || !graphId) return undefined;
    let active = true;
    setBusy(true); setError(''); setReport(null);
    api.post('/api/reports/preview', request).then(({ data }) => {
      if (!active) return;
      setPreview(data);
      setIncluded(data.candidates.filter(candidate => type === 'CITATION_CONTEXT' ? candidate.citationPassages?.length : candidate.hasAbstract).map(candidate => candidate.paperId));
    }).catch(e => { if (active) setError(typeof e.response?.data === 'string' ? e.response.data : 'Could not prepare the report preview.'); })
      .finally(() => { if (active) setBusy(false); });
    return () => { active = false; };
  }, [open, request, type]);

  useEffect(() => {
    if (preset || !preview?.scopes?.length) return;
    const current = preview.scopes.find(option => option.scope === scope);
    if (!current?.available) {
      const firstAvailable = preview.scopes.find(option => option.available);
      if (firstAvailable) setScope(firstAvailable.scope);
    }
  }, [preview, scope]);

  const start = (value = preset, ids = presetPaperIds) => {
    setType(value?.reportType || 'EVIDENCE_SYNTHESIS'); setScope(value?.scope || 'ALL');
    if (onOpen) onOpen(value, ids);
    setOpen(true);
  };
  const generate = async () => {
    setBusy(true); setError('');
    try { const { data } = await api.post('/api/reports/generate', { graphId, reportType: type, scope, includedPaperIds: included }); setReport(data); }
    catch (e) { setError(typeof e.response?.data === 'string' ? e.response.data : 'Report generation failed. Please retry.'); }
    finally { setBusy(false); }
  };
  const candidates = preview?.candidates || [];
  const sourceLabels = new Map(candidates.map(p => [p.paperId, p.title]));
  const passageSources = new Map((report?.sources || []).flatMap(source => (source.citationPassages || []).map(passage => [passage.contextId, { ...passage, focalTitle: source.title }])));

  return <>
    <Button variant="contained" onClick={() => start()} disabled={!graphId}>{buttonLabel}</Button>
    <Dialog open={open} onClose={() => setOpen(false)} fullWidth maxWidth="md">
      <DialogTitle>{report ? 'Research report' : 'Set up evidence report'}</DialogTitle>
      <DialogContent dividers>
        {report ? <>
          <Alert severity="info" sx={{ mb: 2 }}>{report.evidenceBasis} {report.coverageDescription}</Alert>
          <Typography variant="h6">Synthesis</Typography><Typography paragraph sx={{ whiteSpace: 'pre-wrap' }}>{report.synthesis}</Typography>
          {!!report.claims?.length && <><Typography variant="h6">Evidence claims</Typography>{report.claims.map((claim, i) => <Box key={i} sx={{ mb: 1 }}><Typography>{claim.text}</Typography>{claim.caveat && <Typography variant="body2" color="text.secondary">{claim.caveat}</Typography>}<Typography variant="caption" color="text.secondary">Sources: {claim.sourcePaperIds.map(id => sourceLabels.get(id) || id).join('; ')}</Typography></Box>)}</>}
          {!!report.citationAnalyses?.length && <>{report.citationAnalyses.map((citation, i) => { const passage = passageSources.get(citation.contextId); return <Box key={i} sx={{ my: 2 }}><Typography fontWeight="bold">{citation.relation}: {passage?.citingPaperTitle || citation.contextId}</Typography><Typography>{citation.explanation}</Typography>{passage && <><Typography variant="caption" color="text.secondary">Cites: {passage.focalTitle}</Typography><Typography variant="body2" color="text.secondary">“{passage.passage}”</Typography>{passage.citingPaperUrl && <a href={passage.citingPaperUrl} target="_blank" rel="noreferrer">Open citing paper</a>}</>}</Box>; })}</>}
          {!!report.coverageGaps?.length && <Typography><b>Coverage gaps:</b> {report.coverageGaps.join(' ')}</Typography>}
          {!!report.limitations?.length && <Typography><b>Limitations:</b> {report.limitations.join(' ')}</Typography>}
        </> : <>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>Reports use PaperMind’s server-stored papers and classifications. Preview the exact focal papers before generation.</Typography>
          <Box sx={{ display: 'flex', gap: 2, mb: 2 }}>
            <FormControl fullWidth><InputLabel>Report type</InputLabel><Select label="Report type" value={type} disabled={!!preset} onChange={e => setType(e.target.value)}>{REPORT_TYPES.map(x => <MenuItem key={x.value} value={x.value}>{x.label}</MenuItem>)}</Select></FormControl>
            <FormControl fullWidth><InputLabel>Scope</InputLabel><Select label="Scope" value={scope} disabled={!!preset} onChange={e => setScope(e.target.value)}>{(preview?.scopes || [{ scope: 'ALL', available: true }]).filter(x => x.available || x.scope === scope).map(x => <MenuItem key={x.scope} value={x.scope}>{x.scope[0] + x.scope.slice(1).toLowerCase()}</MenuItem>)}</Select></FormControl>
          </Box>
          {busy && <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, my: 2 }}><CircularProgress size={20} /><Typography>Preparing report…</Typography></Box>}
          {error && <Alert severity="error" sx={{ my: 2 }}>{error}</Alert>}
          {preview && <>
            <Alert severity={preview.canGenerate ? 'info' : 'warning'} sx={{ mb: 2 }}>{preview.message} {preview.coverageDescription}</Alert>
            <Typography variant="subtitle1">Included papers ({included.length} selected)</Typography>
            {candidates.map(paper => {
              const eligible = type === 'CITATION_CONTEXT' ? !!paper.citationPassages?.length : paper.hasAbstract;
              return <Box key={paper.paperId} sx={{ display: 'flex', alignItems: 'flex-start', borderBottom: '1px solid', borderColor: 'divider', py: 1 }}>
                <Checkbox checked={included.includes(paper.paperId)} disabled={!eligible} onChange={e => setIncluded(ids => e.target.checked ? [...ids, paper.paperId] : ids.filter(id => id !== paper.paperId))} />
                <Box><Typography>{paper.sourceUrl ? <a href={paper.sourceUrl} target="_blank" rel="noreferrer">{paper.title}</a> : paper.title}</Typography><Typography variant="caption" color="text.secondary">{[paper.authors?.join(', '), paper.year, !eligible ? (type === 'CITATION_CONTEXT' ? 'No citation passage available' : 'No stored abstract') : paper.classifications?.join(', ')].filter(Boolean).join(' · ')}</Typography>
                  {type === 'CITATION_CONTEXT' && paper.citationPassages?.map((passage, i) => <Typography key={i} variant="body2" sx={{ mt: 0.5 }}>“{passage.passage}”</Typography>)}
                </Box>
              </Box>;
            })}
          </>}
        </>}
      </DialogContent>
      <DialogActions><Button onClick={() => setOpen(false)}>{report ? 'Close' : 'Cancel'}</Button>{!report && <Button variant="contained" onClick={generate} disabled={busy || !preview?.canGenerate || !included.length}>Generate report</Button>}</DialogActions>
    </Dialog>
  </>;
}
