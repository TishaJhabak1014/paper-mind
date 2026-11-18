package com.project.main.service;

import com.project.main.entity.CitationEdge;
import com.project.main.entity.ResearchPaper;
import com.project.main.repository.CitationEdgeRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CitationEdgeService {

    @Autowired
    private CitationEdgeRepo citationEdgeRepo;

    @Transactional
    public CitationEdge saveCitationEdge(CitationEdge citationEdge) {
        Integer graphId = citationEdge.getGraph().getId();
        Integer firstPaperId = citationEdge.getFirstResearchPaper().getPaperId();
        Integer secondPaperId = citationEdge.getSecondResearchPaper().getPaperId();

        if (citationEdgeRepo.existsForGraphAndPapers(graphId, firstPaperId, secondPaperId)) {
            return citationEdgeRepo.findForGraphAndPapers(graphId, firstPaperId, secondPaperId).orElse(null);
        }

        try {
            return citationEdgeRepo.save(citationEdge);
        } catch (DataIntegrityViolationException exception) {
            // Concurrent loads can both pass the existence check. Treat the unique-key
            // winner as success, while allowing unrelated database errors to surface.
            return citationEdgeRepo.findForGraphAndPapers(graphId, firstPaperId, secondPaperId)
                    .orElseThrow(() -> exception);
        }
    }

    public List<CitationEdge> getAllCitationEdges() {
        return citationEdgeRepo.findAll();
    }

    public CitationEdge getCitationEdgeById(Integer id) {
        return citationEdgeRepo.findById(id).orElse(null);
    }

    public void deleteCitationEdgeById(Integer id) {
        citationEdgeRepo.deleteById(id);
    }

    public List<CitationEdge> getCitationEdgesByFirstResearchPaper(ResearchPaper firstResearchPaper) {
        return citationEdgeRepo.findByFirstResearchPaper(firstResearchPaper);
    }

    public List<CitationEdge> getCitationEdgesBySecondResearchPaper(ResearchPaper secondResearchPaper) {
        return citationEdgeRepo.findBySecondResearchPaper(secondResearchPaper);
    }

    public List<CitationEdge> getCitationEdgesByFirstAndSecondResearchPapers(ResearchPaper firstResearchPaper, ResearchPaper secondResearchPaper) {
        return citationEdgeRepo.findByFirstResearchPaperAndSecondResearchPaper(firstResearchPaper, secondResearchPaper);
    }
}
