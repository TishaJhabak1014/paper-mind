package com.project.main.service;

import com.project.main.entity.ResearchPaper;
import com.project.main.repository.ResearchPaperRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.List;
import java.util.Optional;

@Service
public class ResearchPaperService {

    private final ResearchPaperRepo researchPaperRepo;

    public ResearchPaperService(ResearchPaperRepo researchPaperRepo) {
        this.researchPaperRepo = researchPaperRepo;
    }

    @Transactional(readOnly = true)
    public List<ResearchPaper> getAllResearchPapers() {
        return researchPaperRepo.findAll();
    }

    @Transactional(readOnly = true)
    public ResearchPaper getResearchPaperById(Integer id) {
        Optional<ResearchPaper> researchPaper = researchPaperRepo.findById(id);
        return researchPaper.orElse(null);
    }

    @Transactional(readOnly = true)
    public ResearchPaper getResearchPaperBySemanticPaperId(String semanticPaperId) {
        Optional<ResearchPaper> researchPaper = researchPaperRepo.findBySemanticPaperId(semanticPaperId);
        return researchPaper.orElse(null);
    }

    /** Loads and refreshes an existing paper while the PostgreSQL large-object fields are in a transaction. */
    @Transactional
    public boolean refreshExistingPaperMetadata(String semanticPaperId, String title, java.time.LocalDateTime publishedDate,
            String abstractText, String sourceLink) {
        ResearchPaper existingPaper = researchPaperRepo.findBySemanticPaperId(semanticPaperId).orElse(null);
        if (existingPaper == null) return false;

        if ((existingPaper.getAbstractText() == null || existingPaper.getAbstractText().isBlank())
                && abstractText != null && !abstractText.isBlank()) existingPaper.setAbstractText(abstractText);
        if ((existingPaper.getTitle() == null || existingPaper.getTitle().isBlank())
                && title != null && !title.isBlank()) existingPaper.setTitle(title);
        if (existingPaper.getPublishedDate() == null && publishedDate != null) existingPaper.setPublishedDate(publishedDate);
        if ((existingPaper.getSourceLink() == null || existingPaper.getSourceLink().isBlank())
                && sourceLink != null && !sourceLink.isBlank()) existingPaper.setSourceLink(sourceLink);

        researchPaperRepo.save(existingPaper);
        return true;
    }

    public ResearchPaper getReferenceBySemanticPaperId(String semanticPaperId) {
        return researchPaperRepo.findIdsBySemanticPaperId(semanticPaperId).stream()
                .findFirst()
                .map(researchPaperRepo::getReferenceById)
                .orElse(null);
    }

    public boolean existsBySemanticPaperId(String semanticPaperId) {
        return researchPaperRepo.existsBySemanticPaperId(semanticPaperId);
    }

    public ResearchPaper saveResearchPaper(ResearchPaper researchPaper) {
        return researchPaperRepo.save(researchPaper);
    }

    @Transactional
    public ResearchPaper updateResearchPaper(Integer id, ResearchPaper newResearchPaperDetails) {
        Optional<ResearchPaper> optionalResearchPaper = this.researchPaperRepo.findById(id);

        if (optionalResearchPaper.isPresent()) {
            ResearchPaper existingResearchPaper = optionalResearchPaper.get();

            if (newResearchPaperDetails.getPaperId().equals(existingResearchPaper.getPaperId())) {
                existingResearchPaper.setTitle(newResearchPaperDetails.getTitle());
                existingResearchPaper.setPublishedDate(newResearchPaperDetails.getPublishedDate());
                existingResearchPaper.setAbstractText(newResearchPaperDetails.getAbstractText());
                existingResearchPaper.setMethodologySummary(newResearchPaperDetails.getMethodologySummary());
                existingResearchPaper.setSourceLink(newResearchPaperDetails.getSourceLink());
                existingResearchPaper.setCitationsCount(newResearchPaperDetails.getCitationsCount());
                existingResearchPaper.setViewsCount(newResearchPaperDetails.getViewsCount());
                existingResearchPaper.setAuthors(newResearchPaperDetails.getAuthors());

                // Save the updated entity
                ResearchPaper updatedResearchPaper = this.researchPaperRepo.save(existingResearchPaper);

                System.out.printf("ResearchPaper with id: %d updated successfully", updatedResearchPaper.getPaperId());
                return updatedResearchPaper;
            }
        }

        System.out.printf("ResearchPaper with id: %d doesn't exist", id);
        return null;
    }


    public void deleteResearchPaperById(Integer id) {
        researchPaperRepo.deleteById(id);
    }
}
