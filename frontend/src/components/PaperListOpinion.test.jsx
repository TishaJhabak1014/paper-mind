import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import PaperListOpinion from './PaperListOpinion';
import { api } from '../api';

vi.mock('../api', () => ({ api: { post: vi.fn() } }));

describe('paper abstract summary action', () => {
  afterEach(cleanup);
  beforeEach(() => vi.clearAllMocks());

  it('requests a summary by paper ID and shows its reference and abstract-only basis', async () => {
    api.post.mockResolvedValue({ data: { paperId: 'paper-1', summary: 'A concise summary.', basis: 'Available abstract' } });
    render(<PaperListOpinion
      papers={[{
        paperId: 'paper-1',
        title: 'Example Research Paper',
        authors: [{ name: 'A. Researcher' }],
        publicationDate: '2022-01-01',
        abstract: 'A supplied abstract.',
        url: 'https://example.org/paper',
        openAccessPdf: { url: 'https://example.org/paper.pdf' },
      }]}
      onGenerateSummaryClick={() => {}}
    />);

    fireEvent.click(screen.getByText('Summarize abstract'));

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/generate-summary', { paperId: 'paper-1' }));
    expect(await screen.findByText('A concise summary.')).toBeInTheDocument();
    expect(screen.getByText(/based only on the available abstract/i)).toBeInTheDocument();
    expect(screen.getByText(/A\. Researcher \(2022\)\. Example Research Paper\./)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open paper landing page' })).toHaveAttribute('href', 'https://example.org/paper');
    expect(screen.getByRole('link', { name: 'Open open-access PDF' })).toHaveAttribute('href', 'https://example.org/paper.pdf');
  });

  it('displays Semantic Scholar TLDR directly without submitting it as an abstract', async () => {
    render(<PaperListOpinion
      papers={[{
        paperId: 'paper-2',
        title: 'TLDR Example',
        authors: [],
        tldr: { text: 'A direct short summary from Semantic Scholar.' },
      }]}
      onGenerateSummaryClick={() => {}}
    />);

    fireEvent.click(screen.getByText('View Semantic Scholar TLDR'));

    expect(await screen.findByText('A direct short summary from Semantic Scholar.')).toBeInTheDocument();
    expect(api.post).not.toHaveBeenCalled();
    expect(screen.queryByText(/based only on the available abstract/i)).not.toBeInTheDocument();
  });
});
