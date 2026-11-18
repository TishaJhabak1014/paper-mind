import React from 'react';
import { List, ListItem, ListItemText, Collapse, Typography, Link, Box, Divider, IconButton, CircularProgress, Button } from '@mui/material';
import PaperDetailOpinion from './PaperDetailOpinion';
import PictureAsPdfIcon from '@mui/icons-material/PictureAsPdf';
import { api } from '../api';

function PaperListOpinion({ papers, onGenerateSummaryClick, highlightedPaperId }) {
  const [summaryOpen, setSummaryOpen] = React.useState({});
  const [summaries, setSummaries] = React.useState({});
  const [summaryErrors, setSummaryErrors] = React.useState({});
  const [loading, setLoading] = React.useState({});

  const generateAISummary = async (id, abstract) => {
    if (loading[id] || summaries[id]) return;
    setLoading(prev => ({ ...prev, [id]: true }));
    setSummaryErrors(prev => ({ ...prev, [id]: null }));
    try {
      const response = await api.post('/api/generate-summary', { abstract });
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
    if (opening && !summaries[paper.paperId] && !loading[paper.paperId]) {
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

  return (
    <List component="nav" sx={{ width: '100%' }}>
      {papers.map((paper,index) => (
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
            {paper.abstract?.trim() ? (
              <Typography
                variant="body2"
                color="primary"
                style={{ cursor: 'pointer', marginTop: 8 }}
                onClick={() => toggleSummary(paper)}
              >
                {summaryOpen[paper.paperId] ? "Hide AI Summary" : "Show AI Summary"}
              </Typography>
            ) : (
              <Typography variant="caption" color="text.secondary" sx={{ mt: 1 }}>
                Abstract not available from Semantic Scholar.
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
                  <PaperDetailOpinion paper={{ ...paper, summary: summaries[paper.paperId] }} /> // pass AI summary to the PaperDetailOpinion
                ) : null}
            </Box>
          </Collapse>
          {index !== papers.length - 1 && <Divider sx={{ mt: 2 }} />}  {/* divider after each item except the last */}
        </React.Fragment>
      ))}
    </List>
  );
}

export default PaperListOpinion;
