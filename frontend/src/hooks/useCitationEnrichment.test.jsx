import { cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../api';
import useCitationEnrichment from './useCitationEnrichment';

vi.mock('../api', () => ({ api: { get: vi.fn() } }));

describe('useCitationEnrichment request scheduling', () => {
  afterEach(cleanup);
  beforeEach(() => vi.clearAllMocks());

  it('keeps citation calls serialized while each paper completes independently', async () => {
    let active = 0;
    let maximumActive = 0;
    api.get.mockImplementation(async url => {
      active += 1;
      maximumActive = Math.max(maximumActive, active);
      await new Promise(resolve => window.setTimeout(resolve, 5));
      active -= 1;
      return { data: [{ paperId: url.split('/').pop() }] };
    });
    const papers = Array.from({ length: 5 }, (_, index) => ({ paperId: `paper-${index + 1}` }));

    const { result } = renderHook(() => useCitationEnrichment(40, papers));
    await waitFor(() => expect(result.current.completed).toBe(5));

    expect(maximumActive).toBe(1);
    expect(result.current.citations['paper-1']).toEqual([{ paperId: 'paper-1' }]);
    expect(api.get).toHaveBeenCalledTimes(5);
  });

  it('waits for the reported cooldown and retries before starting the next paper', async () => {
    const rateLimit = { response: { status: 429, data: 'Semantic Scholar cooldown is active. Retry in about 1 seconds.' } };
    api.get
      .mockRejectedValueOnce(rateLimit)
      .mockResolvedValueOnce({ data: [{ paperId: 'paper-1' }] })
      .mockResolvedValueOnce({ data: [{ paperId: 'paper-2' }] });

    const { result } = renderHook(() => useCitationEnrichment(41, [
      { paperId: 'paper-1' }, { paperId: 'paper-2' },
    ]));
    await waitFor(() => expect(result.current.completed).toBe(2), { timeout: 4000 });

    expect(api.get.mock.calls.map(([url]) => url.split('/').pop())).toEqual([
      'paper-1', 'paper-1', 'paper-2',
    ]);
    expect(result.current.failed).toBe(false);
  });
});
