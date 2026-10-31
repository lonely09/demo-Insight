package com.example.demo.knowledgebase.repository;

import com.example.demo.knowledgebase.domain.KbDocument;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KbDocumentRepository extends JpaRepository<KbDocument, Long> {
}
