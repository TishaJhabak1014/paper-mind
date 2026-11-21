import { useCallback, useEffect, useState } from 'react';
import { api } from '../api';

const pendingOpinionRequests = new Map();

function fetchOpinionGroups(graphId, prompt, papers, force = false) {
  if (!pendingOpinionRequests.has(graphId)) {
    const request = api.post('/api/data/opinions', { graphId, prompt, papers, force })
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
  const [errorMessage, setErrorMessage] = useState('');
  const [retryVersion, setRetryVersion] = useState(0);
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
    setErrorMessage('');
    fetchOpinionGroups(graphId, prompt, papers, retryVersion > 0)
      .then(result => {
        if (!cancelled) {
          setSupporting(result?.supporting || {});
          setOpposing(result?.opposing || {});
          setStatus(hasOpinions(result?.supporting, result?.opposing) ? 'complete' : 'unavailable');
        }
      })
      .catch(requestError => {
        console.error('Could not classify paper opinions:', requestError);
        if (!cancelled) {
          setStatus('failed');
          setErrorMessage(requestError?.response?.data?.message || 'Opinion classification could not be completed. No labels were applied; all retrieved papers remain available.');
        }
      });

    return () => { cancelled = true; };
  }, [graphId, prompt, papersKey, retryVersion]);

  const retry = useCallback(() => setRetryVersion(version => version + 1), []);
  return { supporting, opposing, status, errorMessage, retry };
}

function hasOpinions(supporting, opposing) {
  return [supporting, opposing].some(groups => Object.values(groups || {}).some(ids => Array.isArray(ids) && ids.length > 0));
}
