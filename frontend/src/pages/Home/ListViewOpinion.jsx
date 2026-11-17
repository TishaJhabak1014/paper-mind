import React, { useState, useEffect } from 'react';
import { CircularProgress, Container, Grid, Typography, Paper, Link, IconButton, Collapse, Box, Chip, Button, TextField, MenuItem, Tooltip } from '@mui/material';
import TopNavigation from '../../components/TopNavigation';
import { useLocation, useNavigate } from 'react-router-dom';
import PictureAsPdfIcon from '@mui/icons-material/PictureAsPdf';
import PaperDetailOpinion from '../../components/PaperDetailOpinion';
import { api } from '../../api';

function ListViewOpinion() {
    // State declarations
    const [selectedPaper, setSelectedPaper] = useState(null);
    const location = useLocation();
    const [graphPapers, setGraphPapers] = useState([]);
    const [supportPapers, setSupportPapers] = useState({});
    const [supportingPapersList, setSupportingPapersList] = useState([]);
    const [opposingPapers, setOpposingPapers] = useState({});  // Fix: added state for opposing papers
    const [opposingPapersList, setOpposingPapersList] = useState([]);  // Fix: added state for opposing papers list
    const [isLoading, setIsLoading] = useState(true);
    const [summaryOpen, setSummaryOpen] = useState({});
    const [summaries, setSummaries] = useState({});  // Store the generated summaries
    const [summaryErrors, setSummaryErrors] = useState({});
    const [loading, setLoading] = useState({});  // Track loading state per paper
    const [searchPrompt, setSearchPrompt] = useState('');
    const [currentView, setCurrentView] = useState('List View');
    const [paperSearch, setPaperSearch] = useState('');
    const [selectedYear, setSelectedYear] = useState('');
    const [selectedType, setSelectedType] = useState('');
    const [sortOrder, setSortOrder] = useState('newest');
    const navigate = useNavigate();

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
        if (location.state?.supporting) {
            setSupportPapers(location.state.supporting);
        }
        if (location.state?.opposing) {
            setOpposingPapers(location.state.opposing);  // Fix: Use setOpposingPapers to update opposing papers
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

    const renderOpinionChip = (text, color) => (
        <Tooltip title={text.replace(/_/g, ' ')} placement="top" arrow>
            <Chip label={text.replace(/_/g, ' ')} color={color} size="small" sx={{ mb: 1, maxWidth: '100%', '& .MuiChip-label': { overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' } }} />
        </Tooltip>
    );

    // Function to generate AI summary when requested
    const generateAISummary = async (id, abstract) => {
        if (summaries[id] || loading[id]) return;
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

    const handleSummaryToggle = (id, abstract) => {
      const opening = !summaryOpen[id];
      setSummaryOpen(prev => ({ ...prev, [id]: opening }));
      if (opening && !summaries[id] && !loading[id]) generateAISummary(id, abstract);
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
            onViewChange={(view) => {
                setCurrentView(view);
                if (view === 'Graph View'){
                    navigate('/papers-opinion-graphview', { state: { ...location.state, currentView: 'Graph View' } });
                }
            }}/>
            <Container maxWidth="xl">
                <br />
                <Typography variant="h5" textAlign="left" style={{ fontWeight: 'lighter', color: 'darkblue' }} gutterBottom>
                    Results for {formattedPrompt}
                </Typography>
                <br />
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
                <Grid container spacing={3}>
                    <Grid item xs={12} md={6}>
                        <Typography variant="h6" fontWeight="light" fontSize="16px">Supporting Papers</Typography>
                        {visibleSupportingPapers.length > 0 ? (
                            visibleSupportingPapers.map((entry, index) => (
                                <Paper key={index} sx={paperCardStyle}>
                                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                                        {renderOpinionChip(entry.opinion, getChipColor('Supporting'))}
                                    </div>

                                    <Typography variant="h6" style={{ fontWeight: 'bold', color: '#302C29', fontSize: '20px' }}>{entry.paper.title}</Typography>
                                    <Typography component="span" variant="body2" color="#29436e" fontSize="16px" fontWeight='light'>
                                        {entry.paper.authors.map(author => author.name).join(', ')}
                                    </Typography>
                                    <Typography component="span" variant="body2" color="textPrimary" fontSize="16px">
                                        Year: {new Date(entry.paper.publicationDate).getFullYear()} | {formatPublicationTypes(entry.paper.publicationTypes)}
                                    </Typography>
                                    <Typography>Cited by: {entry.paper.citationCount}</Typography>
                                    {entry.paper.url && (
                                        <Link href={entry.paper.url} target="_blank" rel="noopener noreferrer">
                                            <IconButton color="primary" aria-label="download pdf">
                                                <PictureAsPdfIcon />
                                            </IconButton>
                                        </Link>
                                    )}
                                    {entry.paper.abstract?.trim() ? (
                                        <Typography
                                            variant="body2"
                                            color="primary"
                                            style={{ cursor: 'pointer', marginTop: 8 }}
                                            onClick={() => handleSummaryToggle(entry.paper.paperId, entry.paper.abstract)}
                                        >
                                            {summaryOpen[entry.paper.paperId] ? "Hide AI Summary" : "Show AI Summary"}
                                        </Typography>
                                    ) : (
                                        <Typography variant="caption" color="text.secondary" sx={{ mt: 1 }}>
                                            No abstract available for summary.
                                        </Typography>
                                    )}
                                    <Collapse in={summaryOpen[entry.paper.paperId]} timeout="auto" unmountOnExit>
                                        <Box sx={{ width: '90%', paddingLeft: 4, paddingRight: 4 }}>
                                            {loading[entry.paper.paperId] ? (
                                                <CircularProgress size={24} />
                                            ) : summaryErrors[entry.paper.paperId] ? (
                                                <Box role="alert" sx={{ py: 1 }}>
                                                    <Typography color="error" variant="body2">{summaryErrors[entry.paper.paperId]}</Typography>
                                                    <Button size="small" onClick={() => generateAISummary(entry.paper.paperId, entry.paper.abstract)}>
                                                        Retry summary
                                                    </Button>
                                                </Box>
                                            ) : summaries[entry.paper.paperId] ? (
                                                <PaperDetailOpinion paper={{ ...entry.paper, summary: summaries[entry.paper.paperId] }} />
                                            ) : null}
                                        </Box>
                                    </Collapse>
                                </Paper>
                            ))
                        ) : (
                            <Typography sx={{marginTop:'3%'}}>No supporting papers found.</Typography>
                        )}
                    </Grid>
                    <Grid item xs={12} md={6}>
                        <Typography variant="h6" fontWeight="light" fontSize="16px">Opposing Papers</Typography>
                        {visibleOpposingPapers.length > 0 ? (
                            visibleOpposingPapers.map((entry, index) => (
                                <Paper key={index} sx={paperCardStyle}>
                                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                                        {renderOpinionChip(entry.opinion, getChipColor('Opposing'))}
                                    </div>

                                    <Typography variant="h6" style={{ fontWeight: 'bold', color: '#302C29', fontSize: '20px' }}>{entry.paper.title}</Typography>
                                    <Typography component="span" variant="body2" color="#29436e" fontSize="16px" fontWeight='light'>
                                        {entry.paper.authors.map(author => author.name).join(', ')}
                                    </Typography>
                                    <Typography component="span" variant="body2" color="textPrimary" fontSize="16px">
                                        Year: {new Date(entry.paper.publicationDate).getFullYear()} | {formatPublicationTypes(entry.paper.publicationTypes)}
                                    </Typography>
                                    <Typography>Cited by: {entry.paper.citationCount}</Typography>
                                    {entry.paper.url && (
                                        <Link href={entry.paper.url} target="_blank" rel="noopener noreferrer">
                                            <IconButton color="primary" aria-label="download pdf">
                                                <PictureAsPdfIcon />
                                            </IconButton>
                                        </Link>
                                    )}
                                    {entry.paper.abstract?.trim() ? (
                                        <Typography
                                            variant="body2"
                                            color="primary"
                                            style={{ cursor: 'pointer', marginTop: 8 }}
                                            onClick={() => handleSummaryToggle(entry.paper.paperId, entry.paper.abstract)}
                                        >
                                            {summaryOpen[entry.paper.paperId] ? "Hide AI Summary" : "Show AI Summary"}
                                        </Typography>
                                    ) : (
                                        <Typography variant="caption" color="text.secondary" sx={{ mt: 1 }}>
                                            No abstract available for summary.
                                        </Typography>
                                    )}
                                    <Collapse in={summaryOpen[entry.paper.paperId]} timeout="auto" unmountOnExit>
                                        <Box sx={{ width: '90%', paddingLeft: 4, paddingRight: 4 }}>
                                            {loading[entry.paper.paperId] ? (
                                                <CircularProgress size={24} />
                                            ) : summaryErrors[entry.paper.paperId] ? (
                                                <Box role="alert" sx={{ py: 1 }}>
                                                    <Typography color="error" variant="body2">{summaryErrors[entry.paper.paperId]}</Typography>
                                                    <Button size="small" onClick={() => generateAISummary(entry.paper.paperId, entry.paper.abstract)}>
                                                        Retry summary
                                                    </Button>
                                                </Box>
                                            ) : summaries[entry.paper.paperId] ? (
                                                <PaperDetailOpinion paper={{ ...entry.paper, summary: summaries[entry.paper.paperId] }} />
                                            ) : null}
                                        </Box>
                                    </Collapse>
                                </Paper>
                            ))
                        ) : (
                            
                            <Typography sx={{marginTop:'3%', color:'darkblue'}}>No opposing papers found.</Typography>
                        )}
                    </Grid>
                </Grid>
            </Container>
        </div>
    );
}

export default ListViewOpinion;
