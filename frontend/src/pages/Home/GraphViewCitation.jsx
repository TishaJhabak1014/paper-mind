import React, { useState, useEffect, useRef } from 'react';
import { CircularProgress, Container, Grid, Typography, MenuItem, Select, FormControl, Box, Modal, Link } from '@mui/material';
import PaperListOpinion from '../../components/PaperListOpinion';
import TopNavigation from '../../components/TopNavigation';
import { useLocation, useNavigate } from 'react-router-dom';
import { DataSet } from 'vis-data';
import { Network } from 'vis-network/standalone';
import PictureAsPdfIcon from '@mui/icons-material/PictureAsPdf';


function GraphViewCitation() {
  const [selectedPaper, setSelectedPaper] = useState(null);
  const [selectedPaperDetails, setSelectedPaperDetails] = useState(null); // Store details of clicked paper
  const [isModalOpen, setIsModalOpen] = useState(false); // State for modal visibility
  const [citingPapers, setCitingPapers] = useState([]); // State to store citing papers
  const location = useLocation();
  const navigate = useNavigate(); // Use navigate for switching views
  const [graphPapers, setGraphPapers] = useState({});
  const [viewType, setViewType] = useState(location.state?.selectedFilter || 'ALL');
  const [searchPrompt, setSearchPrompt] = useState('');
  const [currentView, setCurrentView] = useState('Graph View');
  const [graphViewType, setGraphViewType] = useState('Graph View');
  const visJsRef = useRef(null); 
  const networkRef = useRef(null);
  const [highlightedPaperId, setHighlightedPaperId] = useState(null);
  const [citations, setCitations] = useState({});
  const [supporting, setSupporting] = useState({});
  const [opposing, setOpposing] = useState({});
  const [isLoading, setIsLoading] = useState(true);

  // Capitalize words function
  const capitalizeWords = (str) => {
    return str
      .split(' ')
      .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
      .join(' ');
  };

  // Effect to load data from location.state
  useEffect(() => {
    const loadData = () => {
      if(location.state?.papers){
        setGraphPapers(location.state.papers);
      }
      if (location.state?.graphPapers) {
        setGraphPapers(location.state.graphPapers);
      }
      if (location.state?.citations) {
        setCitations(location.state.citations);
      }
      if (location.state?.supporting) {
        setSupporting(location.state.supporting);
      }
      if (location.state?.opposing) {
        setOpposing(location.state.opposing);
      }
      if (location.state?.prompt) {
        setSearchPrompt(location.state.prompt);
      }
      if (location.state?.graphViewType) {
        setGraphViewType(location.state.graphViewType);
      }
      if (location.state?.currentView){
        setCurrentView(location.state.currentView);
      }
      setIsLoading(false);
    };

    loadData();
  }, [location.state]);

  const modalStyle = {
    position: 'absolute',
    top: '50%',
    left: '50%',
    transform: 'translate(-50%, -50%)',
    width: '60%',
    bgcolor: 'background.paper',
    boxShadow: 24,
    borderRadius: '1.5rem',
    p: 4,
    overflow: 'auto',
    maxHeight: '60vh',
  };

  const drawGraph = () => {
    if (!graphPapers || !visJsRef.current) return;
  
    const nodes = new DataSet();
    const edges = new DataSet();
    const addedNodes = new Set();
    const addedEdges = new Set(); // Track added edges
    const nodesWithIncomingEdges = new Set(); // Track nodes with incoming edges

    const citationCounts = new Map();
    graphPapers.forEach(paper => citationCounts.set(paper.paperId, Number(paper.citationCount) || 0));
    Object.values(citations).forEach(items => {
      if (!Array.isArray(items)) return;
      items.forEach(item => {
        const paper = item?.citingPaper;
        if (paper?.paperId) citationCounts.set(paper.paperId, Number(paper.citationCount) || 0);
      });
    });
    const maxCitationCount = Math.max(0, ...citationCounts.values());
    const getNodeStyle = (paperId, label) => {
      const count = citationCounts.get(paperId) || 0;
      const ratio = maxCitationCount > 0 ? Math.log1p(count) / Math.log1p(maxCitationCount) : 0;
      const red = Math.round(174 - ratio * 153);
      const green = Math.round(211 - ratio * 102);
      const blue = Math.round(255 - ratio * 13);
      const background = `rgb(${red}, ${green}, ${blue})`;
      return {
        size: 12 + ratio * 23,
        color: { background, border: '#3978c5', highlight: { background: '#ffca5c', border: '#9b6500' } },
        title: `${label}\nCitations: ${count.toLocaleString()}`,
      };
    };
  
    // Helper function to add a node if it hasn't been added yet
    const addNode = (paperId, label) => {
      if (!addedNodes.has(paperId)) {
        nodes.add({ id: paperId, label, ...getNodeStyle(paperId, label) });
        addedNodes.add(paperId);
      }
    };
  
    // Helper function to format citing papers
    const formatCitingLabel = (citingPaperObj) => {
      const citingAuthors = citingPaperObj.citingPaper.authors || [];
      const firstAuthor = citingAuthors.length > 0 ? citingAuthors[0].name : 'Unknown';
      const publicationDate = citingPaperObj.citingPaper.publicationDate;
      const year = publicationDate ? new Date(publicationDate).getFullYear() : 'Unknown';
      return `${firstAuthor} ${year}`;
    };
  
    // Modified function to fetch citation details (including PDF link)
    const formatCitationDetails = (citingPaperObj) => {
      const citingAuthors = citingPaperObj.citingPaper.authors || [];
      const firstAuthor = citingAuthors.length > 0 ? citingAuthors[0].name : 'Unknown';
      const publicationDate = citingPaperObj.citingPaper.publicationDate;
      const year = publicationDate ? new Date(publicationDate).getFullYear() : 'Unknown';
      const pdfLink = citingPaperObj.citingPaper.openAccessPdf?.url || null;
  
      return {
        label: `${firstAuthor} ${year}`,
        pdfLink: pdfLink,
      };
    };
  
    // Helper function to add edges between citing and cited papers
    const addEdges = (paperId) => {
      if (citations[paperId]) {
        const citingPapers = citations[paperId];
        if (Array.isArray(citingPapers) && citingPapers.length > 0){
          citingPapers.forEach((citingPaperObj) => {
            const citingPaperId = citingPaperObj.citingPaper.paperId;
            const citingLabel = formatCitingLabel(citingPaperObj);
            if (!citingPaperId) return; // skip if citing paper ID is invalid
    
            // Add the citing paper node if not already added
            if (!addedNodes.has(citingPaperId)) {
              addNode(citingPaperId, citingLabel);
            }
    
            // Create an edge identifier to check for duplicates
            const edgeId = `${citingPaperId}->${paperId}`;
    
            // Add the edge from the citing paper to the cited paper only if it doesn't already exist
            if (!addedEdges.has(edgeId)) {
              edges.add({
                from: citingPaperId,
                to: paperId,
              });
              addedEdges.add(edgeId); 
    
              // Mark the cited paper (paperId) as having an incoming edge
              nodesWithIncomingEdges.add(paperId);
            }
          });
        }   
      }
    };
  
    // Process data based on viewType
    if (viewType === 'ALL') {
      // Handle Citations (flat structure)
      Object.keys(citations).forEach((paperId) => {
        const label = formatLabel(paperId);
        addNode(paperId, label);
        addEdges(paperId);
      });
    } else {
      // Handle Supporting or Opposing (nested structure)
      const dataToUse = viewType === 'SUPPORTING' ? supporting : opposing;
  
      Object.keys(dataToUse).forEach((category) => {
        const papers = dataToUse[category];
        if (Array.isArray(papers)) {
          papers.forEach((paperId) => {
            const label = formatLabel(paperId);
            addNode(paperId, label);
            addEdges(paperId);
          });
        }
      });
    }
  
    // Use nodes and edges to render the graph
    const data = { nodes, edges };
    const options = {
      nodes: {
        shape: 'dot',
        font: { size: 12 },
      },
      edges: { arrows: { to: true }, color: { color: '#678bb8', highlight: '#d18a00' } },
      physics: { barnesHut: { gravitationalConstant: -2500, springLength: 130 }, stabilization: { iterations: 150 } },
      interaction: { hover: true, tooltipDelay: 150 },
    };
  
    networkRef.current?.destroy();
    const network = new Network(visJsRef.current, data, options);
    networkRef.current = network;
    network.once('stabilizationIterationsDone', () => network.fit({ animation: { duration: 350, easingFunction: 'easeInOutQuad' } }));
    // Add event listener for node click to show modal
    network.on('click', (params) => {
      if (params.nodes.length > 0) {
        const paperId = params.nodes[0];
        const isInSearchResults = graphPapers.some(paper => paper.paperId === paperId);
        if (isInSearchResults) {
          setHighlightedPaperId(paperId);
          const selectedMap = viewType === 'SUPPORTING' ? supporting : opposing;
          const visibleIds = viewType === 'ALL' ? new Set(graphPapers.map(paper => paper.paperId)) : new Set(Object.values(selectedMap).flat());
          if (!visibleIds.has(paperId)) setViewType('ALL');
        }
  
        // Check if the clicked node has incoming edges
        if (nodesWithIncomingEdges.has(paperId) && !isInSearchResults) {
          const paperDetails = graphPapers.find(p => p.paperId === paperId);
          const citingPapersList = citations[paperId]?.map(citingPaperObj => {
            const citingDetails = formatCitationDetails(citingPaperObj);
            return citingDetails;
          }) || [];
  
          setSelectedPaperDetails(paperDetails);
          setCitingPapers(citingPapersList);
          setIsModalOpen(true);
        }
      }
    });
  };
  
  // function to format node labels with first author and year
  const formatLabel = (paperId) => {
    // find the paper by ID and return first author + year as the label
    const paper = graphPapers.find(p => p.paperId === paperId);
    if (paper) {
      const firstAuthor = paper.authors?.[0]?.name || 'Unknown';
      const year = paper.publicationDate ? new Date(paper.publicationDate).getFullYear() : 'Year unknown';
      return `${firstAuthor} (${year})`;
    }
    return paperId;
  };

  // Render graph when graphPapers or viewType changes
  useEffect(() => {
    drawGraph();
  }, [graphPapers, viewType, citations, supporting, opposing]);

  useEffect(() => {
    if (!highlightedPaperId) return undefined;
    const frame = requestAnimationFrame(() => {
      document.getElementById(`paper-${highlightedPaperId}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    });
    const clearHighlight = window.setTimeout(() => setHighlightedPaperId(null), 2400);
    return () => { cancelAnimationFrame(frame); window.clearTimeout(clearHighlight); };
  }, [highlightedPaperId, viewType]);

  useEffect(() => () => networkRef.current?.destroy(), []);

  const visiblePapers = viewType === 'ALL'
    ? graphPapers
    : graphPapers.filter(paper => Object.values(viewType === 'SUPPORTING' ? supporting : opposing).some(ids => Array.isArray(ids) && ids.includes(paper.paperId)));

  if (isLoading) {
    return (
      <Container
        maxWidth="xl"
        style={{ height: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center' }}
      >
        <CircularProgress />
      </Container>
    );
  }

  return (
    <div>
      <TopNavigation
        currentView={currentView}
        onViewChange={(view) => {
          setCurrentView(view);
          if (view === 'List View'){
              navigate('/list-view', { state: { ...location.state, currentView: 'List View' } });
          }
        }}
        papers={graphPapers}
        viewtype={graphViewType}
        prompt={searchPrompt}
        citations={citations}
        selectedFilter={viewType}
        supporting={supporting}
        opposing={opposing}
      />
      <Container maxWidth="xl">
        <br />
        <Typography sx={{ color: 'darkblue', fontWeight: 'light', fontSize: '20px' }}>
          Showing results for {capitalizeWords(searchPrompt)}
        </Typography>

        {/* Dropdown for switching between ALL, SUPPORTING, OPPOSING */}
        <Box sx={{ display: 'flex', justifyContent: 'flex-end', marginBottom: '1rem' }}>
          <FormControl>
            <Select
              value={viewType}
              onChange={(e) => setViewType(e.target.value)}
              displayEmpty
            >
              <MenuItem value="ALL">All</MenuItem>
              <MenuItem value="SUPPORTING">Supporting</MenuItem>
              <MenuItem value="OPPOSING">Opposing</MenuItem>
            </Select>
          </FormControl>
        </Box>

        <Grid container spacing={3}>
          <Grid item xs={12} md={6}>
            {visiblePapers.length > 0 ? (
              <PaperListOpinion papers={visiblePapers} onGenerateSummaryClick={setSelectedPaper} highlightedPaperId={highlightedPaperId} />
            ) : (
              <Typography>No papers match this graph filter.</Typography>
            )}
          </Grid>
          <Grid item xs={12} md={6}>
            <div ref={visJsRef} style={{ width: '100%', minWidth: 0, height: '600px', border: '1px solid lightgray', overflow: 'hidden' }}></div>
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
              Larger, darker nodes represent papers with more citations. Arrows point from citing papers to the papers they cite.
            </Typography>
          </Grid>
        </Grid>
      </Container>

      {/* Modal for paper details */}
      <Modal
        open={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        aria-labelledby="paper-details-modal"
      >
        <Box sx={modalStyle}>
          {selectedPaperDetails && (
            <>
              <Typography variant="h6" sx={{ fontWeight: 'bold', color:'black'}}>
                {selectedPaperDetails.authors?.[0]?.name || 'Unknown'} {selectedPaperDetails.publicationDate ? new Date(selectedPaperDetails.publicationDate).getFullYear() : 'Year unknown'}
              </Typography>
              <Typography variant="body1" sx={{ marginTop: 1 , fontWeight: 'light'}}>
                {selectedPaperDetails.title || 'No title available'}
              </Typography>
              {selectedPaperDetails.openAccessPdf?.url && (
              <Link href={selectedPaperDetails.openAccessPdf.url} target="_blank" rel="noopener noreferrer">
              <PictureAsPdfIcon style={{ fontSize: '1.5rem', color: 'red' }} />
              </Link>
              )}

            </>
          )}
          <Typography variant="subtitle1" component="h2" sx={{ marginTop: 2, fontWeight: 'light' }}>
          Cited By:
          </Typography>
          <ul style={{ padding: 0, margin: '10px 0', listStyleType: 'none' }}>
          {citingPapers.map((citingPaper, index) => (
          <li key={index} style={{ marginBottom: '8px'}}>
            <span style={{ fontStyle: 'italic', fontWeight: 'light', color: 'darkblue' }}>{citingPaper.label}</span>
            {citingPaper.pdfLink && (
              <>
              &nbsp;
              <Link href={citingPaper.pdfLink} target="_blank" rel="noopener noreferrer">
                <PictureAsPdfIcon style={{ fontSize: '1rem', verticalAlign: 'middle', color: 'red' }} />
              </Link>
            </>
          )}
          </li>
        ))}
        </ul>
        </Box>
      </Modal>
    </div>
  );
}

export default GraphViewCitation;
