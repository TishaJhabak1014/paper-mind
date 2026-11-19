import React, { useState, useEffect } from 'react';
import { CircularProgress, Container, Grid, Typography, TextField, MenuItem, Box } from '@mui/material';
import PaperListOpinion from '../../components/PaperListOpinion';
import TopNavigation from '../../components/TopNavigation';
import { useLocation, Link, useNavigate } from 'react-router-dom';
import GraphProgressNotice from '../../components/GraphProgressNotice';
import useCitationEnrichment from '../../hooks/useCitationEnrichment';
import useOpinionEnrichment from '../../hooks/useOpinionEnrichment';
import EvidenceReportTool from '../../components/EvidenceReportTool';


function ListViewCitation() {
  const [graphPapers, setGraphPapers] = useState([]); // Store all papers
  const [filteredPapers, setFilteredPapers] = useState([]); // Store filtered papers
  const [currentView, setCurrentView] = useState('List View');
  const [isLoading, setIsLoading] = useState(true); // Local loading state
  const [years, setYears] = useState([]); // Store unique publication years
  const navigate = useNavigate();

  // Properly declaring state variables for filters
  const [selectedYear, setSelectedYear] = useState('');
  const [selectedAuthor, setSelectedAuthor] = useState('');
  const [selectedType, setSelectedType] = useState('');
  const [paperSearch, setPaperSearch] = useState('');
  const [sortOrder, setSortOrder] = useState('citations');
  const location = useLocation();
  const [searchPrompt, setSearchPrompt] = useState('');
  const { citations, completed: citationProgress, total: citationTotal, loading: citationsLoading, failed: citationsFailed, loadingPaperIds, failedPaperIds, retryFailed: retryCitationLoads } =
    useCitationEnrichment(location.state?.graphId, Array.isArray(location.state?.papers) ? location.state.papers : [], location.state?.citations || {});
  const { supporting, opposing, status: opinionStatus, errorMessage: opinionError, retry: retryOpinions } = useOpinionEnrichment(
    location.state?.graphId,
    location.state?.prompt || '',
    Array.isArray(location.state?.papers) ? location.state.papers : [],
    location.state?.supporting || {},
    location.state?.opposing || {},
  );
  

  useEffect(() => {
    if (location.state?.papers) {
      setGraphPapers(location.state.papers);
      setFilteredPapers(location.state.papers);
      setIsLoading(false);

      const uniqueYears = [...new Set(location.state.papers.map(paper => new Date(paper.publicationDate).getFullYear()))];
      setYears(uniqueYears.sort((a, b) => b - a));
    }
    if (location.state?.prompt){
      setSearchPrompt(location.state.prompt);
    }
    console.log(searchPrompt);
  }, [location.state?.papers]);

  const capitalizeWords = (str) => {
    return str
      .split(' ')               
      .map(word => word.charAt(0).toUpperCase() + word.slice(1)) 
      .join(' ');               
  };

  const formattedPrompt = capitalizeWords(searchPrompt);

  const publicationTypes = [...new Set(graphPapers.flatMap(paper => paper.publicationTypes || []))].sort();

  const applyFilters = () => {
    let filtered = [...graphPapers];
    const query = paperSearch.trim().toLocaleLowerCase();

    if (query) {
      filtered = filtered.filter(paper => [paper.title, paper.abstract, ...(paper.authors || []).map(author => author.name)]
        .filter(Boolean).some(value => value.toLocaleLowerCase().includes(query)));
    }

    if (selectedYear) {
      filtered = filtered.filter(paper => new Date(paper.publicationDate).getFullYear() === parseInt(selectedYear));
    }
    if (selectedAuthor) {
      filtered = filtered.filter(paper => (paper.authors || []).some(author => author.name.toLocaleLowerCase().includes(selectedAuthor.toLocaleLowerCase())));
    }
    if (selectedType) {
      filtered = filtered.filter(paper => paper.publicationTypes && paper.publicationTypes.includes(selectedType));
    }

    filtered.sort((a, b) => {
      if (sortOrder === 'citations') {
        return (Number(b.citationCount) || 0) - (Number(a.citationCount) || 0);
      }
      const yearA = a.publicationDate ? new Date(a.publicationDate).getFullYear() : 0;
      const yearB = b.publicationDate ? new Date(b.publicationDate).getFullYear() : 0;
      return sortOrder === 'oldest' ? yearA - yearB : yearB - yearA;
    });
    setFilteredPapers(filtered);
  };

  useEffect(() => {
    if (selectedYear || selectedAuthor || selectedType || paperSearch || sortOrder) {
      applyFilters();
    } else {
      setFilteredPapers(graphPapers); // Reset to initial state when all filters are cleared
    }
  }, [selectedYear, selectedAuthor, selectedType, graphPapers, paperSearch, sortOrder]);

  if (isLoading) {
    return (
      <Container maxWidth="xl" style={{ height: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <CircularProgress />
      </Container>
    );
  }

  return (
    <div>
      <TopNavigation currentView={currentView} 
      papers={graphPapers}
      viewtype="CITATION"
      prompt={searchPrompt}
      citations={citations}
      graphId={location.state?.graphId}
      supporting={supporting}
      opposing={opposing}
      selectedFilter={location.state?.selectedFilter}
      onViewChange={(view) => {
        setCurrentView(view);
        if (view === 'Graph View') {
            // navigate back to the Graph View and pass the current state
            navigate('/papers-graphview', { state: { ...location.state, papers: graphPapers, citations, graphId: location.state?.graphId, currentView: 'Graph View' } });
        }
    }}
      />
      <Box sx={{ maxWidth: 1280, mx: 'auto', px: 2, py: 1, display: 'flex', justifyContent: 'flex-end' }}><EvidenceReportTool graphId={location.state?.graphId} /></Box>
      <Container maxWidth="xl">
        {citationsLoading && <GraphProgressNotice message={`Loading citation connections (${citationProgress} of ${citationTotal})…`} progress={{ completed: citationProgress, total: citationTotal }} />}
        {citationsFailed && !citationsLoading && <GraphProgressNotice message="Some citation links are temporarily unavailable. Your paper results are still loaded." error actionLabel="Retry citation links" onAction={retryCitationLoads} />}
        {opinionStatus === 'loading' && <GraphProgressNotice message="Grouping papers by supporting and opposing findings…" />}
        {opinionStatus === 'failed' && <GraphProgressNotice message={opinionError} warning actionLabel="Retry classification" onAction={retryOpinions} />}
        {opinionStatus === 'unavailable' && <GraphProgressNotice message="No papers could be confidently classified from the supplied metadata. All papers remain available without opinion labels." warning />}
        <Grid container spacing={3}>
          {/* Filters on the left with fixed positioning */}
          <Grid item xs={3} style={{ position: 'fixed', top: '100px', left: 0, height: '80vh', overflowY: 'auto' }}>
            <Box sx={{ paddingRight: '20px' }}>
              <Typography variant="h6" sx={{ color: 'black', fontWeight: 'light' }}>Apply Filters</Typography>
              <br></br>
              <TextField fullWidth label="Search papers" placeholder="Title, author, or abstract" value={paperSearch} onChange={e => setPaperSearch(e.target.value)} sx={{ mb: 2 }} />
              <TextField fullWidth select label="Sort results" value={sortOrder} onChange={e => setSortOrder(e.target.value)} sx={{ mb: 2 }}>
                <MenuItem value="citations">Most cited first</MenuItem>
                <MenuItem value="newest">Newest first</MenuItem>
                <MenuItem value="oldest">Oldest first</MenuItem>
              </TextField>
              <Typography sx={{ color: 'black', fontWeight: 'light' }}>Year</Typography>
              <TextField
                fullWidth
                select
                label="Filter by Year"
                variant="outlined"
                value={selectedYear}
                onChange={(e) => setSelectedYear(e.target.value)}
                style={{ marginBottom: '20px' }}
              >
                <MenuItem value="">All Years</MenuItem>
                {years.map(year => (
                  <MenuItem key={year} value={year}>{year}</MenuItem>
                ))}
              </TextField>
              <Typography sx={{ color: 'black', fontWeight: 'light' }}>Author</Typography>
              <TextField
                fullWidth
                label="Filter by Author"
                variant="outlined"
                value={selectedAuthor}
                onChange={(e) => setSelectedAuthor(e.target.value)}
                style={{ marginBottom: '20px' }}
              />
              <Typography sx={{ color: 'black', fontWeight: 'light' }}>Publication Type</Typography>
              <TextField
                fullWidth
                select
                label="Filter by Type"
                variant="outlined"
                value={selectedType}
                onChange={(e) => setSelectedType(e.target.value)}
                style={{ marginBottom: '20px'}}
              >
                <MenuItem value="">All Types</MenuItem>
                {publicationTypes.map(type => <MenuItem key={type} value={type}>{type.replace(/([a-z])([A-Z])/g, '$1 $2')}</MenuItem>)}
              </TextField>
            </Box>
          </Grid>

          {/* Papers List on the right */}
          <Grid item xs={9} style={{ marginLeft: '25%', marginTop:'2.2%', height: 'calc(100vh - 10vh)', overflowY: 'auto' }}>
            <br></br>
            <Typography  sx={{ color: 'darkblue', fontWeight: 'light', fontSize:'20px' }}>Showing results for {formattedPrompt} </Typography>
            {filteredPapers.length > 0 ? (
              <PaperListOpinion 
                papers={filteredPapers} 
                loadingCitationIds={loadingPaperIds}
                failedCitationIds={failedPaperIds}
                onGenerateSummaryClick={() => {}}
              />
            ) : (
              <Typography>No papers found.</Typography>
            )}
          </Grid>
        </Grid>
      </Container>
    </div>
  );
}

export default ListViewCitation;
