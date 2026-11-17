import { render, screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import App from './App';

test('renders the PaperMind search screen', () => {
  render(<App />);
  expect(screen.getByRole('heading', { name: /hi there, what would you like to know/i })).toBeInTheDocument();
});
