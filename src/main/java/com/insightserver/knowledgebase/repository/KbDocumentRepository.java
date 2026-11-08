package com.insightserver.knowledgebase.repository;

import com.insightserver.knowledgebase.domain.KbDocument;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KbDocumentRepository extends JpaRepository<KbDocument, Long> {
}
