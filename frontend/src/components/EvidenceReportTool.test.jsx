import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import EvidenceReportTool from './EvidenceReportTool';
import { api } from '../api';

vi.mock('../api', () => ({ api: { post: vi.fn() } }));

describe('evidence report setup', () => {
  afterEach(cleanup);
  beforeEach(() => vi.clearAllMocks());

  it('previews server-selected sources and generates only the selected paper IDs', async () => {
    api.post.mockImplementation((path) => {
      if (path === '/api/reports/preview') return Promise.resolve({ data: {
        graphId: 4,
        graphType: 'OPINION',
        reportType: 'EVIDENCE_SYNTHESIS',
        scope: 'ALL',
        researchQuestion: 'Research question',
        scopes: [{ scope: 'ALL', paperCount: 1, available: true, message: '' }],
        candidates: [{ paperId: 'p1', title: 'Example Paper', authors: ['A. Author'], year: 2020, sourceUrl: 'https://example.org/p1', hasAbstract: true, classifications: ['SUPPORTING: group'], citationPassages: [] }],
        canGenerate: true,
        message: 'Review these papers.',
        coverageDescription: 'One stored abstract.',
      } });
      return Promise.resolve({ data: {
        graphId: 4,
        graphType: 'OPINION',
        reportType: 'EVIDENCE_SYNTHESIS',
        scope: 'ALL',
        researchQuestion: 'Research question',
        synthesis: 'An abstract-based synthesis.',
        evidenceBasis: 'Abstract-based synthesis',
        claims: [],
        citationAnalyses: [],
        coverageGaps: [],
        limitations: [],
        sources: [],
        coverageDescription: 'One stored abstract.',
      } });
    });

    render(<EvidenceReportTool graphId={4} />);
    fireEvent.click(screen.getByRole('button', { name: 'Create evidence report' }));
    expect(await screen.findByText('Example Paper')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Generate report' }));

    await waitFor(() => expect(api.post).toHaveBeenLastCalledWith('/api/reports/generate', {
      graphId: 4,
      reportType: 'EVIDENCE_SYNTHESIS',
      scope: 'ALL',
      includedPaperIds: ['p1'],
    }));
    expect(await screen.findByText('An abstract-based synthesis.')).toBeInTheDocument();
  });
});
