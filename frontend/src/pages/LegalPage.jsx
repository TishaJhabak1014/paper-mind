import React from 'react';
import { Box, Container, Link, Typography } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';

const content = {
  privacy: {
    title: 'Privacy Policy',
    sections: [
      ['What PaperMind does', 'PaperMind helps users discover research papers, view citation relationships, and create AI-generated summaries from paper abstracts.'],
      ['Research requests', 'When you search, PaperMind sends the query to its application services to retrieve scholarly metadata and generate the requested results.'],
      ['Google Drive', 'The optional “Save to Google Drive” feature is started only when you select it. PaperMind requests permission to create and manage only the files it creates in your Google Drive. The feature uploads the selected summary directly from your browser to Google Drive. PaperMind does not use this permission to read unrelated Drive files.'],
      ['Data sharing and retention', 'PaperMind does not sell personal information. It does not create user accounts or store Google Drive file contents on its servers. Service providers may process requests as needed to operate the application.'],
      ['Contact', 'For privacy questions, contact tishajhabak22@gmail.com.'],
    ],
  },
  terms: {
    title: 'Terms of Service',
    sections: [
      ['Using PaperMind', 'PaperMind is provided for research discovery and productivity. You are responsible for reviewing sources and verifying any AI-generated summary before relying on it.'],
      ['Third-party content', 'Paper metadata and links may come from third-party scholarly sources. Their availability, accuracy, and licensing are outside PaperMind’s control.'],
      ['Google Drive', 'If you choose to save a summary to Google Drive, you authorize PaperMind to create that selected file in your Drive. You can revoke access at any time from your Google Account permissions settings.'],
      ['Contact', 'For questions about these terms, contact tishajhabak22@gmail.com.'],
    ],
  },
};

function LegalPage({ type }) {
  const page = content[type];
  return (
    <Container maxWidth="md" sx={{ py: 7 }}>
      <Typography component={RouterLink} to="/" sx={{ textDecoration: 'none', fontWeight: 700, color: 'primary.main' }}>
        PaperMind
      </Typography>
      <Typography variant="h3" component="h1" sx={{ mt: 3, mb: 1 }}>{page.title}</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 4 }}>Last updated: November 17, 2026</Typography>
      {page.sections.map(([heading, body]) => (
        <Box key={heading} sx={{ mb: 3 }}>
          <Typography variant="h6" component="h2" sx={{ mb: 0.75 }}>{heading}</Typography>
          <Typography>{body}</Typography>
        </Box>
      ))}
      <Typography variant="body2" color="text.secondary">
        <Link component={RouterLink} to="/privacy">Privacy Policy</Link> · <Link component={RouterLink} to="/terms">Terms of Service</Link>
      </Typography>
    </Container>
  );
}

export default LegalPage;
