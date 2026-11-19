import React, { useState, useEffect } from 'react';
import { CircularProgress, Container, Grid, Typography, Paper, Link, IconButton, Collapse, Box, Chip, Button, TextField, MenuItem, Tooltip } from '@mui/material';
import TopNavigation from '../../components/TopNavigation';
import { useLocation, useNavigate } from 'react-router-dom';
import PictureAsPdfIcon from '@mui/icons-material/PictureAsPdf';
import PaperDetailOpinion from '../../components/PaperDetailOpinion';
import PaperListOpinion from '../../components/PaperListOpinion';
import GraphProgressNotice from '../../components/GraphProgressNotice';
import useOpinionEnrichment from '../../hooks/useOpinionEnrichment';
import { api } from '../../api';
import EvidenceReportTool from '../../components/EvidenceReportTool';

function ListViewOpinion() {
    // State declarations
    const [selectedPaper, setSelectedPaper] = useState(null);
    const location = useLocation();
    const [graphPapers, setGraphPapers] = useState([]);
    const [supportingPapersList, setSupportingPapersList] = useState([]);
    const [opposingPapersList, setOpposingPapersList] = useState([]);  // Fix: added state for opposing papers list
    const [isLoading, setIsLoading] = useState(true);
    const [summaryOpen, setSummaryOpen] = useState({});
    const [summaries, setSummaries] = useState({});  // Store the generated summaries
    const [summaryLabels, setSummaryLabels] = useState({});
    const [summaryErrors, setSummaryErrors] = useState({});
    const [loading, setLoading] = useState({});  // Track loading state per paper
    const [searchPrompt, setSearchPrompt] = useState('');
    const [currentView, setCurrentView] = useState('List View');
    const [paperSearch, setPaperSearch] = useState('');
    const [selectedYear, setSelectedYear] = useState('');
    const [selectedType, setSelectedType] = useState('');
    const [sortOrder, setSortOrder] = useState('newest');
    const navigate = useNavigate();
    const papersForEnrichment = Array.isArray(location.state?.papers)
        ? location.state.papers
        : location.state?.graphPapers || location.state?.papers?.Papers || [];
    const { supporting: supportPapers, opposing: opposingPapers, status: opinionStatus, errorMessage: opinionError, retry: retryOpinions } = useOpinionEnrichment(
        location.state?.graphId,
        location.state?.prompt || searchPrompt,
        papersForEnrichment,
        location.state?.supporting || {},
        location.state?.opposing || {},
    );
    const [supportingVisibleCount, setSupportingVisibleCount] = useState(5);
    const [opposingVisibleCount, setOpposingVisibleCount] = useState(5);

    // Define a consistent style for paper cards
    const paperCardStyle = {  // Fix: Define paperCardStyle properly
        padding: '20px',
        margin: '10px 0',
        backgroundColor: '#f2f2f2',
        minHeight: '150px',
        display: 'flex',
        flexDirection: 'column',
        justifyContent: 'space-between',
        '&:hover': {
            backgroundColor: '#e6e6e6'
        }
    };

    const capitalizeWords = (str) => {
        return str
          .split(' ')               
          .map(word => word.charAt(0).toUpperCase() + word.slice(1)) 
          .join(' ');               
    };
    
    const formattedPrompt = capitalizeWords(searchPrompt);

    useEffect(() => {
        if (location.state?.papers) {
            setGraphPapers(location.state.papers);
        }
        if (location.state?.prompt) {
            setSearchPrompt(location.state.prompt);
        }
        setIsLoading(false);
    }, [location.state]);

    useEffect(() => {
        const calculatePapers = (papersMap) => {
            if (!papersMap || typeof papersMap !== 'object' || !graphPapers) {
                return [];
            }
          
            const result = [];
            Object.keys(papersMap).forEach(opinion => {
                const paperIds = papersMap[opinion];
                paperIds.forEach(paperId => {
                    const paper = graphPapers.find(p => p.paperId === paperId);
                    if (paper) {
                        result.push({ paper, opinion });
                    }
                });
            });
            return result;
        };

        setSupportingPapersList(calculatePapers(supportPapers));
        setOpposingPapersList(calculatePapers(opposingPapers));  // Fix: Use setOpposingPapersList to update opposing papers list
    }, [supportPapers, opposingPapers, graphPapers]);

    const formatPublicationTypes = (types) => {
      if (!types) {  
        return '';  
      }
      return types.join(' ');
    };

    const publicationYears = [...new Set(graphPapers.map(paper => paper.publicationDate ? new Date(paper.publicationDate).getFullYear() : null).filter(Boolean))].sort((a, b) => b - a);
    const publicationTypes = [...new Set(graphPapers.flatMap(paper => paper.publicationTypes || []))].sort();
    const filterAndSortEntries = entries => entries.filter(({ paper, opinion }) => {
        const query = paperSearch.trim().toLocaleLowerCase();
        const searchable = [paper.title, paper.abstract, opinion, ...(paper.authors || []).map(author => author.name)].filter(Boolean).join(' ').toLocaleLowerCase();
        return (!query || searchable.includes(query))
            && (!selectedYear || (paper.publicationDate && String(new Date(paper.publicationDate).getFullYear()) === selectedYear))
            && (!selectedType || (paper.publicationTypes || []).includes(selectedType));
    }).sort((a, b) => {
        const yearA = a.paper.publicationDate ? new Date(a.paper.publicationDate).getFullYear() : 0;
        const yearB = b.paper.publicationDate ? new Date(b.paper.publicationDate).getFullYear() : 0;
        return sortOrder === 'oldest' ? yearA - yearB : yearB - yearA;
    });
    const visibleSupportingPapers = filterAndSortEntries(supportingPapersList);
    const visibleOpposingPapers = filterAndSortEntries(opposingPapersList);
    const shownSupportingPapers = visibleSupportingPapers.slice(0, supportingVisibleCount);
    const shownOpposingPapers = visibleOpposingPapers.slice(0, opposingVisibleCount);

    const renderOpinionChip = (text, color) => (
        <Tooltip title={text.replace(/_/g, ' ')} placement="top" arrow>
            <Chip label={text.replace(/_/g, ' ')} color={color} size="small" sx={{ mb: 1, maxWidth: '100%', '& .MuiChip-label': { overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' } }} />
        </Tooltip>
    );

    // Function to generate AI summary when requested
    const generateAISummary = async (id) => {
        if (summaries[id] || loading[id]) return;
        setLoading(prev => ({ ...prev, [id]: true }));
        setSummaryErrors(prev => ({ ...prev, [id]: null }));
        try {
            const response = await api.post('/api/generate-summary', { paperId: id });
            setSummaries(prev => ({ ...prev, [id]: response.data.summary }));
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

    const handleSummaryToggle = (id, abstract, tldr) => {
      const opening = !summaryOpen[id];
      setSummaryOpen(prev => ({ ...prev, [id]: opening }));
      if (opening && !abstract?.trim() && tldr?.trim() && !summaries[id]) {
        setSummaries(prev => ({ ...prev, [id]: tldr.trim() }));
        setSummaryLabels(prev => ({ ...prev, [id]: 'Semantic Scholar TLDR' }));
      } else if (opening && abstract?.trim() && !summaries[id] && !loading[id]) {
        setSummaryLabels(prev => ({ ...prev, [id]: 'Summary of available abstract' }));
        generateAISummary(id);
      }
    };

    if (isLoading) {
        return (
            <Container maxWidth="xl" style={{ height: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                <CircularProgress />
            </Container>
        );
    }

    // Opinion to color mapping
    const opinionColorMap = {
      Supporting: 'success',  
      Opposing: 'error',      
      Neutral: 'default'      
    };

    const getChipColor = (opinion) => opinionColorMap[opinion] || 'default';

    return (
        <div>
            <TopNavigation 
            currentView={currentView}
            papers={graphPapers}
            viewtype="OPINION"
            supporting={supportPapers}
            opposing={opposingPapers}
            prompt={searchPrompt}
            graphId={location.state?.graphId}
            onViewChange={(view) => {
                setCurrentView(view);
                if (view === 'Graph View'){
                    navigate('/papers-opinion-graphview', { state: { ...location.state, papers: graphPapers, supporting: supportPapers, opposing: opposingPapers, graphId: location.state?.graphId, currentView: 'Graph View' } });
                }
            }}/>
            <Box sx={{ maxWidth: 1280, mx: 'auto', px: 2, py: 1, display: 'flex', justifyContent: 'flex-end' }}><EvidenceReportTool graphId={location.state?.graphId} /></Box>
            <Container maxWidth="xl">
                <br />
                <Typography variant="h5" textAlign="left" style={{ fontWeight: 'lighter', color: 'darkblue' }} gutterBottom>
                    Results for {formattedPrompt}
                </Typography>
                <br />
                {opinionStatus === 'loading' && <GraphProgressNotice message="Papers are ready. Grouping them by supporting and opposing findings…" />}
                {opinionStatus === 'failed' && <GraphProgressNotice message={opinionError} warning actionLabel="Retry classification" onAction={retryOpinions} />}
                {opinionStatus === 'unavailable' && <GraphProgressNotice message="No papers could be confidently classified from the supplied metadata. All retrieved papers remain available without opinion labels." warning />}
                <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap', mb: 3 }}>
                    <TextField label="Search papers" placeholder="Title, author, opinion, or abstract" value={paperSearch} onChange={e => setPaperSearch(e.target.value)} size="small" sx={{ minWidth: 260, flex: 1 }} />
                    <TextField select label="Year" value={selectedYear} onChange={e => setSelectedYear(e.target.value)} size="small" sx={{ minWidth: 130 }}>
                        <MenuItem value="">All years</MenuItem>{publicationYears.map(year => <MenuItem key={year} value={String(year)}>{year}</MenuItem>)}
                    </TextField>
                    <TextField select label="Document type" value={selectedType} onChange={e => setSelectedType(e.target.value)} size="small" sx={{ minWidth: 170 }}>
                        <MenuItem value="">All types</MenuItem>{publicationTypes.map(type => <MenuItem key={type} value={type}>{type.replace(/([a-z])([A-Z])/g, '$1 $2')}</MenuItem>)}
                    </TextField>
                    <TextField select label="Sort by year" value={sortOrder} onChange={e => setSortOrder(e.target.value)} size="small" sx={{ minWidth: 150 }}>
                        <MenuItem value="newest">Newest first</MenuItem><MenuItem value="oldest">Oldest first</MenuItem>
                    </TextField>
                </Box>
                {opinionStatus !== 'complete' ? <PaperListOpinion papers={graphPapers} onGenerateSummaryClick={() => {}} /> : <Grid container spacing={3}>
                    <Grid item xs={12} md={6}>
                        <Typography variant="h6" fontWeight="light" fontSize="16px">Supporting Papers</Typography>
                        {visibleSupportingPapers.length > 0 ? (
                            shownSupportingPapers.map((entry, index) => (
                                <Paper key={index} sx={paperCardStyle}>
                                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                                        {renderOpinionChip(entry.opinion, getChipColor('Supporting'))}
                                    </div>

                                    <Typography variant="h6" style={{ fontWeight: 'bold', color: '#302C29', fontSize: '20px' }}>{entry.paper.title}</Typography>
                                    <Typography component="span" variant="body2" color="#29436e" fontSize="16px" fontWeight='light'>
                                        {entry.paper.authors.map(author => author.name).join(', ')}
                                    </Typography>
                                    <Typography component="span" variant="body2" color="textPrimary" fontSize="16px">
                                        Year: {entry.paper.publicationDate ? new Date(entry.paper.publicationDate).getFullYear() : 'Unknown'} | {formatPublicationTypes(entry.paper.publicationTypes)}
                                    </Typography>
                                    <Typography>Cited by: {entry.paper.citationCount}</Typography>
                                    {entry.paper.url && (
                                        <Link href={entry.paper.url} target="_blank" rel="noopener noreferrer">
                                            <IconButton color="primary" aria-label="download pdf">
                                                <PictureAsPdfIcon />
                                            </IconButton>
                                        </Link>
                                    )}
                                    {entry.paper.abstract?.trim() || entry.paper.tldr?.text?.trim() ? (
                                        <Typography
                                            variant="body2"
                                            color="primary"
                                            style={{ cursor: 'pointer', marginTop: 8 }}
                                            onClick={() => handleSummaryToggle(entry.paper.paperId, entry.paper.abstract, entry.paper.tldr?.text)}
                                        >
                                            {summaryOpen[entry.paper.paperId] ? "Hide details" : entry.paper.abstract?.trim() ? "Summarize abstract" : "View Semantic Scholar TLDR"}
                                        </Typography>
                                    ) : (
                                        <Typography variant="caption" color="text.secondary" sx={{ mt: 1 }}>
                                            Abstract not available from Semantic Scholar or the available fallback sources.
                                        </Typography>
                                    )}
                                    <Collapse in={summaryOpen[entry.paper.paperId]} timeout="auto" unmountOnExit>
                                        <Box sx={{ width: '90%', paddingLeft: 4, paddingRight: 4 }}>
                                            {loading[entry.paper.paperId] ? (
                                                <CircularProgress size={24} />
                                            ) : summaryErrors[entry.paper.paperId] ? (
                                                <Box role="alert" sx={{ py: 1 }}>
                                                    <Typography color="error" variant="body2">{summaryErrors[entry.paper.paperId]}</Typography>
                                                    <Button size="small" onClick={() => generateAISummary(entry.paper.paperId)}>
                                                        Retry summary
                                                    </Button>
                                                </Box>
                                            ) : summaries[entry.paper.paperId] ? (
                                                <PaperDetailOpinion paper={{ ...entry.paper, summary: summaries[entry.paper.paperId], summaryLabel: summaryLabels[entry.paper.paperId] }} />
                                            ) : null}
                                        </Box>
                                    </Collapse>
                                </Paper>
                            ))
                        ) : (
                            <Typography sx={{marginTop:'3%'}}>{opinionStatus === 'loading' ? 'Supporting papers will appear when grouping finishes.' : 'No supporting papers found.'}</Typography>
                        )}
                        {shownSupportingPapers.length < visibleSupportingPapers.length && <Button size="small" onClick={() => setSupportingVisibleCount(count => count + 5)}>Show next 5 supporting papers</Button>}
                    </Grid>
                    <Grid item xs={12} md={6}>
                        <Typography variant="h6" fontWeight="light" fontSize="16px">Opposing Papers</Typography>
                        {visibleOpposingPapers.length > 0 ? (
                            shownOpposingPapers.map((entry, index) => (
                                <Paper key={index} sx={paperCardStyle}>
                                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                                        {renderOpinionChip(entry.opinion, getChipColor('Opposing'))}
                                    </div>

                                    <Typography variant="h6" style={{ fontWeight: 'bold', color: '#302C29', fontSize: '20px' }}>{entry.paper.title}</Typography>
                                    <Typography component="span" variant="body2" color="#29436e" fontSize="16px" fontWeight='light'>
                                        {entry.paper.authors.map(author => author.name).join(', ')}
                                    </Typography>
                                    <Typography component="span" variant="body2" color="textPrimary" fontSize="16px">
                                        Year: {entry.paper.publicationDate ? new Date(entry.paper.publicationDate).getFullYear() : 'Unknown'} | {formatPublicationTypes(entry.paper.publicationTypes)}
                                    </Typography>
                                    <Typography>Cited by: {entry.paper.citationCount}</Typography>
                                    {entry.paper.url && (
                                        <Link href={entry.paper.url} target="_blank" rel="noopener noreferrer">
                                            <IconButton color="primary" aria-label="download pdf">
                                                <PictureAsPdfIcon />
                                            </IconButton>
                                        </Link>
                                    )}
                                    {entry.paper.abstract?.trim() || entry.paper.tldr?.text?.trim() ? (
                                        <Typography
                                            variant="body2"
                                            color="primary"
                                            style={{ cursor: 'pointer', marginTop: 8 }}
                                            onClick={() => handleSummaryToggle(entry.paper.paperId, entry.paper.abstract, entry.paper.tldr?.text)}
                                        >
                                            {summaryOpen[entry.paper.paperId] ? "Hide details" : entry.paper.abstract?.trim() ? "Summarize abstract" : "View Semantic Scholar TLDR"}
                                        </Typography>
                                    ) : (
                                        <Typography variant="caption" color="text.secondary" sx={{ mt: 1 }}>
                                            Abstract not available from Semantic Scholar or the available fallback sources.
                                        </Typography>
                                    )}
                                    <Collapse in={summaryOpen[entry.paper.paperId]} timeout="auto" unmountOnExit>
                                        <Box sx={{ width: '90%', paddingLeft: 4, paddingRight: 4 }}>
                                            {loading[entry.paper.paperId] ? (
                                                <CircularProgress size={24} />
                                            ) : summaryErrors[entry.paper.paperId] ? (
                                                <Box role="alert" sx={{ py: 1 }}>
                                                    <Typography color="error" variant="body2">{summaryErrors[entry.paper.paperId]}</Typography>
                                                    <Button size="small" onClick={() => generateAISummary(entry.paper.paperId)}>
                                                        Retry summary
                                                    </Button>
                                                </Box>
                                            ) : summaries[entry.paper.paperId] ? (
                                                <PaperDetailOpinion paper={{ ...entry.paper, summary: summaries[entry.paper.paperId], summaryLabel: summaryLabels[entry.paper.paperId] }} />
                                            ) : null}
                                        </Box>
                                    </Collapse>
                                </Paper>
                            ))
                        ) : (
                            <Typography sx={{marginTop:'3%', color:'darkblue'}}>{opinionStatus === 'loading' ? 'Opposing papers will appear when grouping finishes.' : 'No opposing papers found.'}</Typography>
                        )}
                        {shownOpposingPapers.length < visibleOpposingPapers.length && <Button size="small" onClick={() => setOpposingVisibleCount(count => count + 5)}>Show next 5 opposing papers</Button>}
                    </Grid>
                </Grid>}
            </Container>
        </div>
    );
}

export default ListViewOpinion;
