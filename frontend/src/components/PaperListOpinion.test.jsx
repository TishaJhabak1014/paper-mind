import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import PaperListOpinion from './PaperListOpinion';
import { api } from '../api';

vi.mock('../api', () => ({ api: { post: vi.fn() } }));

describe('paper abstract summary action', () => {
  afterEach(cleanup);
  beforeEach(() => vi.clearAllMocks());

  it('shows a deduplicated title, author/year, abstract-only basis, and no source links', async () => {
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
    expect(screen.getByText('A. Researcher (2022)')).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { name: 'Example Research Paper' })).toHaveLength(2);
    expect(screen.queryByRole('link', { name: 'Open paper landing page' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Open open-access PDF' })).not.toBeInTheDocument();
  });

  it('copies the visible summary content followed by linked references as text', async () => {
    api.post.mockResolvedValue({ data: { paperId: 'paper-1', summary: 'A concise summary.', basis: 'Available abstract' } });
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
    render(<PaperListOpinion papers={[{
      paperId: 'paper-1', title: 'Example Research Paper', authors: [{ name: 'A. Researcher' }],
      publicationDate: '2022-01-01', abstract: 'Abstract.', url: 'https://example.org/paper',
      openAccessPdf: { url: 'https://example.org/paper.pdf' },
    }]} onGenerateSummaryClick={() => {}} />);

    fireEvent.click(screen.getByText('Summarize abstract'));
    await screen.findByText('A concise summary.');
    fireEvent.click(screen.getByRole('button', { name: 'Copy summary' }));

    await waitFor(() => expect(writeText).toHaveBeenCalledWith(expect.stringContaining(
      'Example Research Paper\n\nThis summary is based only on the available abstract, not the full paper.\n\nA. Researcher (2022)\n\nA concise summary.\n\nReferences\nPaper landing page: https://example.org/paper\nOpen-access PDF: https://example.org/paper.pdf',
    )));
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
