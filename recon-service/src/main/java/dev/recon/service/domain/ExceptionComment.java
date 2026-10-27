package dev.recon.service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "exception_comment")
public class ExceptionComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "exception_id", nullable = false)
    private long exceptionId;
    @Column(nullable = false)
    private String author;
    @Column(nullable = false)
    private String body;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ExceptionComment() {
    }

    public ExceptionComment(long exceptionId, String author, String body) {
        this.exceptionId = exceptionId;
        this.author = author;
        this.body = body;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public long getExceptionId() { return exceptionId; }
    public String getAuthor() { return author; }
    public String getBody() { return body; }
    public Instant getCreatedAt() { return createdAt; }
}
