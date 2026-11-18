import { useEffect, useState } from 'react';
import { api } from '../api';

const pendingOpinionRequests = new Map();

function fetchOpinionGroups(graphId, prompt, papers) {
  if (!pendingOpinionRequests.has(graphId)) {
    const request = api.post('/api/data/opinions', { graphId, prompt, papers })
      .then(response => response.data)
      .finally(() => pendingOpinionRequests.delete(graphId));
    pendingOpinionRequests.set(graphId, request);
  }
  return pendingOpinionRequests.get(graphId);
}

export default function useOpinionEnrichment(graphId, prompt, papers, initialSupporting = {}, initialOpposing = {}) {
  const [supporting, setSupporting] = useState(initialSupporting || {});
  const [opposing, setOpposing] = useState(initialOpposing || {});
  const [status, setStatus] = useState(graphId && !hasOpinions(initialSupporting, initialOpposing) ? 'loading' : 'complete');
  const [error, setError] = useState(false);
  const papersKey = (papers || []).map(paper => paper.paperId).filter(Boolean).join(',');

  useEffect(() => {
    setSupporting(initialSupporting || {});
    setOpposing(initialOpposing || {});
    if (!graphId || hasOpinions(initialSupporting, initialOpposing)) {
      setStatus('complete');
      return undefined;
    }

    let cancelled = false;
    setStatus('loading');
    setError(false);
    fetchOpinionGroups(graphId, prompt, papers)
      .then(result => {
        if (!cancelled) {
          setSupporting(result?.supporting || {});
          setOpposing(result?.opposing || {});
          setStatus('complete');
        }
      })
      .catch(requestError => {
        console.error('Could not classify paper opinions:', requestError);
        if (!cancelled) {
          setStatus('failed');
          setError(true);
        }
      });

    return () => { cancelled = true; };
  }, [graphId, prompt, papersKey]);

  return { supporting, opposing, status, error };
}

function hasOpinions(supporting, opposing) {
  return Object.keys(supporting || {}).length > 0 || Object.keys(opposing || {}).length > 0;
}
