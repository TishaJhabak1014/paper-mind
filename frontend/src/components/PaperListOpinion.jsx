import React from 'react';
import { List, ListItem, ListItemText, Collapse, Typography, Link, Box, Divider, IconButton, CircularProgress, Button } from '@mui/material';
import PaperDetailOpinion from './PaperDetailOpinion';
import PictureAsPdfIcon from '@mui/icons-material/PictureAsPdf';
import { api } from '../api';

function PaperListOpinion({ papers = [], onGenerateSummaryClick, highlightedPaperId, loadingCitationIds = [], failedCitationIds = [] }) {
  const [summaryOpen, setSummaryOpen] = React.useState({});
  const [summaries, setSummaries] = React.useState({});
  const [summaryErrors, setSummaryErrors] = React.useState({});
  const [summaryLabels, setSummaryLabels] = React.useState({});
  const [loading, setLoading] = React.useState({});
  const [visibleCount, setVisibleCount] = React.useState(5);
  const papersKey = papers.map(paper => paper.paperId).join(',');

  React.useEffect(() => {
    setVisibleCount(5);
  }, [papersKey]);

  React.useEffect(() => {
    if (!highlightedPaperId) return;
    const paperIndex = papers.findIndex(paper => paper.paperId === highlightedPaperId);
    if (paperIndex < 0) return;
    const countThroughHighlightedPaper = Math.ceil((paperIndex + 1) / 5) * 5;
    setVisibleCount(current => Math.max(current, countThroughHighlightedPaper));
  }, [highlightedPaperId, papersKey]);

  const generateAISummary = async (id, abstract) => {
    if (loading[id] || summaries[id]) return;
    setLoading(prev => ({ ...prev, [id]: true }));
    setSummaryErrors(prev => ({ ...prev, [id]: null }));
    try {
      const response = await api.post('/api/generate-summary', { paperId: id, abstract });
      setSummaries(prev => ({ ...prev, [id]: response.data }));
    } catch (error) {
      console.error('Error fetching AI summary:', error);
      const message = typeof error.response?.data === 'string'
        ? error.response.data
        : 'The AI service is temporarily unavailable. Please retry.';
      setSummaryErrors(prev => ({ ...prev, [id]: message }));
    } finally {
      setLoading(prev => ({ ...prev, [id]: false }));
    }
  };

  const toggleSummary = (paper) => {
    const opening = !summaryOpen[paper.paperId];
    setSummaryOpen(prev => ({ ...prev, [paper.paperId]: opening }));
    const tldr = paper.tldr?.text?.trim();
    if (opening && tldr && !paper.abstract?.trim() && !summaries[paper.paperId]) {
      setSummaries(prev => ({ ...prev, [paper.paperId]: tldr }));
      setSummaryLabels(prev => ({ ...prev, [paper.paperId]: 'Semantic Scholar TLDR' }));
    } else if (opening && paper.abstract?.trim() && !summaries[paper.paperId] && !loading[paper.paperId]) {
      setSummaryLabels(prev => ({ ...prev, [paper.paperId]: 'AI Summary of Abstract' }));
      generateAISummary(paper.paperId, paper.abstract);
    }
  };

  const onPaperSelect = (id) =>{
    onGenerateSummaryClick(id);
  };

  const formatAuthors = (authors) => {
    return authors.map(author => author.name).join(', ');
  };

  const formatPublicationTypes = (types) => {
    if (!types) {  
      return '';  
    }
    return types.join(' ');
  }

  const visiblePapers = papers.slice(0, visibleCount);

  return (
    <>
    <List component="nav" sx={{ width: '100%' }}>
      {visiblePapers.map((paper,index) => (
        <React.Fragment key={paper.paperId}>
          <ListItem id={`paper-${paper.paperId}`} alignItems="flex-start" sx={{ flexDirection: "column", alignItems: "flex-start", width: '100%', cursor:'pointer', scrollMarginTop: '100px', backgroundColor: highlightedPaperId === paper.paperId ? '#fff4cc' : 'transparent', transition: 'background-color 700ms ease', '&:hover': {
                backgroundColor: highlightedPaperId === paper.paperId ? '#ffecad' : '#f4f4f4'
              } }} onClick={() => onPaperSelect(paper.paperId)}>
          <Typography
              variant="h6"
              style={{ fontWeight: 'bold', color: '#302C29', fontSize:'20px' }}  
            >
              {paper.title}
            </Typography>
            <ListItemText 
              
              secondary={
                <>
                  <Typography component="span" variant="body2" color="#29436e" fontSize="16px" fontWeight='light'>
                    {formatAuthors(paper.authors)}
                  </Typography>
                  <br />
                  <Typography component="span" variant="body2" color="textPrimary" fontSize="16px">
                    Year: {paper.publicationDate ? new Date(paper.publicationDate).getFullYear() : 'Unknown'} | {formatPublicationTypes(paper.publicationTypes)}
                  </Typography>
                  <br />
                  <Typography component="span" variant="body2" color="textPrimary" fontSize="16px">
                    Cited by: {paper.citationCount}
                  </Typography>
                  {loadingCitationIds.includes(paper.paperId) && (
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75, mt: 0.5 }}>
                      <CircularProgress size={12} />
                      <Typography variant="caption" color="text.secondary">Loading citation links…</Typography>
                    </Box>
                  )}
                  {failedCitationIds.includes(paper.paperId) && (
                    <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>
                      Citation links could not be loaded.
                    </Typography>
                  )}
                  <br />
                  {paper.url && (
                  <Link href={paper.url} target="_blank" rel="noopener noreferrer">
                <IconButton color="primary" aria-label="download pdf">
                  <PictureAsPdfIcon />
                </IconButton>
              </Link>
            )}
                </>
              }
            />
            {paper.abstract?.trim() || paper.tldr?.text?.trim() ? (
              <Typography
                variant="body2"
                color="primary"
                style={{ cursor: 'pointer', marginTop: 8 }}
                onClick={() => toggleSummary(paper)}
              >
                {summaryOpen[paper.paperId] ? "Hide details" : paper.abstract?.trim() ? "Show AI Summary" : "View Semantic Scholar TLDR"}
              </Typography>
            ) : (
              <Typography variant="caption" color="text.secondary" sx={{ mt: 1 }}>
                Abstract not available from Semantic Scholar or the available fallback sources.
              </Typography>
            )}
          </ListItem>
          <Collapse in={summaryOpen[paper.paperId]} timeout="auto" unmountOnExit>
            <Box sx={{ maxWidth: '100%', paddingLeft: 4, paddingRight: 4, overflowWrap:'break-word', boxSizing:'border-box' }}>
                {loading[paper.paperId] ? (
                  <CircularProgress size={24} /> // show loading spinner
                ) : summaryErrors[paper.paperId] ? (
                  <Box role="alert" sx={{ py: 1 }}>
                    <Typography color="error" variant="body2">{summaryErrors[paper.paperId]}</Typography>
                    <Button size="small" onClick={() => generateAISummary(paper.paperId, paper.abstract)}>
                      Retry summary
                    </Button>
                  </Box>
                ) : summaries[paper.paperId] ? (
                  <PaperDetailOpinion paper={{ ...paper, summary: summaries[paper.paperId], summaryLabel: summaryLabels[paper.paperId] }} />
                ) : null}
            </Box>
          </Collapse>
          {index !== visiblePapers.length - 1 && <Divider sx={{ mt: 2 }} />}
        </React.Fragment>
      ))}
    </List>
    {visiblePapers.length < papers.length && (
      <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 0.5, py: 1 }}>
        <Typography variant="caption" color="text.secondary">
          Showing {visiblePapers.length} of {papers.length} papers
        </Typography>
        <Button size="small" onClick={() => setVisibleCount(count => Math.min(count + 5, papers.length))}>
          Show next 5 papers
        </Button>
      </Box>
    )}
    </>
  );
}

export default PaperListOpinion;
