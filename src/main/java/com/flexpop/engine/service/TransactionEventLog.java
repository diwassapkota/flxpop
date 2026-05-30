package com.flexpop.engine.service;

import com.flexpop.engine.domain.PublicIdGenerator;
import com.flexpop.engine.domain.entity.TransactionEventEntity;
import com.flexpop.engine.domain.repo.TransactionEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Append-only ledger writer. Every state change goes through here so we have
 * one place to enforce the invariant.
 */
@Service
public class TransactionEventLog {

    public enum Source { ENGINE, GATEWAY, MERCHANT, SYSTEM }

    private final TransactionEventRepository repo;

    public TransactionEventLog(TransactionEventRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public TransactionEventEntity append(Long transactionId,
                                          String type,
                                          Source source,
                                          Map<String, Object> payload) {
        TransactionEventEntity event = new TransactionEventEntity();
        event.setPublicId(PublicIdGenerator.forEvent());
        event.setTransactionId(transactionId);
        event.setType(type);
        event.setSource(source.name());
        event.setPayload(payload);
        return repo.save(event);
    }

    public List<TransactionEventEntity> history(Long transactionId) {
        return repo.findByTransactionIdOrderByOccurredAtAsc(transactionId);
    }
}
