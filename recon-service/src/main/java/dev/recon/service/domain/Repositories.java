package dev.recon.service.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

/** Spring Data repositories for the ops domain. */
public final class Repositories {

    private Repositories() {
    }

    public interface ReconRunRepository extends JpaRepository<ReconRun, Long> {
        Page<ReconRun> findAllByOrderByIdDesc(Pageable pageable);
    }

    public interface ReconExceptionRepository extends JpaRepository<ReconException, Long>,
            JpaSpecificationExecutor<ReconException> {
    }

    public interface CommentRepository extends JpaRepository<ExceptionComment, Long> {
        List<ExceptionComment> findByExceptionIdOrderByCreatedAtAsc(long exceptionId);
    }

    public interface AdjustmentRepository extends JpaRepository<AdjustmentJournal, Long> {
        Optional<AdjustmentJournal> findByExceptionId(long exceptionId);

        Page<AdjustmentJournal> findByStatus(AdjustmentJournal.Status status, Pageable pageable);
    }

    public interface AuditRepository extends JpaRepository<AuditEntry, Long>, JpaSpecificationExecutor<AuditEntry> {
        List<AuditEntry> findByEntityTypeAndEntityIdOrderByIdAsc(String entityType, String entityId);
    }

    public interface AppUserRepository extends JpaRepository<AppUser, String> {
    }
}
