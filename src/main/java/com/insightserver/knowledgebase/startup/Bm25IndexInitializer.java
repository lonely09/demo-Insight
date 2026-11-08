package com.insightserver.knowledgebase.startup;

import com.insightserver.knowledgebase.repository.KbChunkRepository;
import com.insightserver.knowledgebase.retrieval.bm25.Bm25Index;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 启动时从 MySQL 全量重建进程内 BM25 索引 */
@Component
public class Bm25IndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Bm25IndexInitializer.class);

    private final KbChunkRepository chunkRepository;
    private final Bm25Index bm25Index;

    public Bm25IndexInitializer(KbChunkRepository chunkRepository, Bm25Index bm25Index) {
        this.chunkRepository = chunkRepository;
        this.bm25Index = bm25Index;
    }

    @Override
    public void run(ApplicationArguments args) {
        long start = System.currentTimeMillis();
        int count = 0;
        for (KbChunkRepository.ChunkTextRow row : chunkRepository.findAllTextRows()) {
            bm25Index.addChunk(row.getId(), row.getContent());
            count++;
        }
        log.info("BM25 索引加载完成：chunks={}, terms 索引已就绪，耗时 {}ms", count, System.currentTimeMillis() - start);
    }
}
