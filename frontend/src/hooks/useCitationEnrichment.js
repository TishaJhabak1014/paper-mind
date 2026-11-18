import { useEffect, useRef, useState } from 'react';
import { api } from '../api';

const SAMPLE_SIZE = 5;
const pendingCitationRequests = new Map();

function fetchPaperCitations(graphId, paperId) {
  const requestKey = `${graphId}:${paperId}`;
  if (!pendingCitationRequests.has(requestKey)) {
    const request = (async () => {
      for (let attempt = 0; ; attempt += 1) {
        try {
          const response = await api.get(`/api/data/citations/${graphId}/${encodeURIComponent(paperId)}`);
          return Array.isArray(response.data) ? response.data : [];
        } catch (error) {
          if (error.response?.status !== 429 || attempt >= 2) throw error;
          const body = error.response.data;
          const message = typeof body === 'string' ? body : body?.message || '';
          const seconds = Number(message.match(/about\s+(\d+)\s+seconds?/i)?.[1]);
          const delay = Number.isFinite(seconds) && seconds > 0
            ? Math.min(seconds, 60) * 1000
            : 5000 * (attempt + 1);
          await new Promise(resolve => window.setTimeout(resolve, delay));
        }
      }
    })()
      .finally(() => pendingCitationRequests.delete(requestKey));
    pendingCitationRequests.set(requestKey, request);
  }
  return pendingCitationRequests.get(requestKey);
}

export default function useCitationEnrichment(graphId, papers, initialCitations = {}) {
  const [citations, setCitations] = useState(initialCitations || {});
  const [completed, setCompleted] = useState(0);
  const [failed, setFailed] = useState(false);
  const [loadingPaperIds, setLoadingPaperIds] = useState([]);
  const [failedPaperIds, setFailedPaperIds] = useState([]);
  const [retryVersion, setRetryVersion] = useState(0);
  const successfulPaperIds = useRef(new Set());
  const paperIds = (papers || []).slice(0, SAMPLE_SIZE).map(paper => paper.paperId).filter(Boolean);
  const paperIdsKey = paperIds.join(',');

  useEffect(() => {
    setCitations(initialCitations || {});
    successfulPaperIds.current = new Set(
      Object.keys(initialCitations || {}).filter(id => paperIds.includes(id)),
    );
  }, [graphId, paperIdsKey]);

  useEffect(() => {
    if (!graphId || paperIds.length === 0) {
      setCompleted(paperIds.length);
      return undefined;
    }

    let cancelled = false;
    setFailed(false);
    const alreadyLoaded = paperIds.filter(id =>
      Object.prototype.hasOwnProperty.call(initialCitations || {}, id) || successfulPaperIds.current.has(id),
    );
    setCompleted(alreadyLoaded.length);

    const pending = paperIds.filter(id =>
      !Object.prototype.hasOwnProperty.call(initialCitations || {}, id) && !successfulPaperIds.current.has(id),
    );
    setLoadingPaperIds(pending);
    setFailedPaperIds([]);
    pending.forEach(async paperId => {
      try {
        const paperCitations = await fetchPaperCitations(graphId, paperId);
        if (!cancelled) {
          successfulPaperIds.current.add(paperId);
          setCitations(previous => ({ ...previous, [paperId]: paperCitations }));
        }
      } catch (error) {
        console.error(`Could not load citation links for ${paperId}:`, error);
        if (!cancelled) {
          setFailed(true);
          setFailedPaperIds(previous => [...previous, paperId]);
        }
      } finally {
        if (!cancelled) {
          setLoadingPaperIds(previous => previous.filter(id => id !== paperId));
          setCompleted(previous => previous + 1);
        }
      }
    });

    return () => { cancelled = true; };
  }, [graphId, paperIdsKey, retryVersion]);

  const retryFailed = () => {
    if (failedPaperIds.length === 0) return;
    setFailed(false);
    setFailedPaperIds([]);
    setRetryVersion(version => version + 1);
  };

  return {
    citations,
    completed,
    total: paperIds.length,
    loading: Boolean(graphId) && completed < paperIds.length,
    failed,
    loadingPaperIds,
    failedPaperIds,
    retryFailed,
  };
}
