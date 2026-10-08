package dev.mstaszew.campaign.common.repo

import dev.mstaszew.campaign.common.domain.ApplicationEntity
import dev.mstaszew.campaign.common.domain.ApplicationStatus
import org.springframework.data.mongodb.repository.MongoRepository
import java.time.Instant

interface ApplicationRepository :
    MongoRepository<ApplicationEntity, String>,
    ApplicationRepositoryCustom {

    fun countByStatus(status: ApplicationStatus): Long

    fun countByAppliedAtAfter(after: Instant): Long

    fun existsByCompanyKeyAndRoleKey(companyKey: String, roleKey: String): Boolean
}

/**
 * The two dedupe reads are hand-written rather than derived.
 *
 * Spring Data MongoDB does support First/Top prefixes, but "first" without an
 * explicit sort returns an arbitrary document: Mongo has no natural order the
 * way a Postgres heap scan does. The dedupe reference that lands in the event
 * ledger would then vary run to run for the same input, so both reads pin an
 * ascending _id sort and keep the result deterministic.
 */
interface ApplicationRepositoryCustom {

    /**
     * Dedup check 1 + 3: exact id or normalized URL match (any age).
     *
     * Pass null for [urlValue] when the candidate has no URL. The empty string
     * is not equivalent: it matches every application stored without one.
     */
    fun findFirstByIdOrUrl(idValue: String, urlValue: String?): ApplicationEntity?

    /**
     * Dedup check 2: company match. The caller must exclude non-company
     * sentinel keys (confidential, anonymous, ...) before consulting this.
     */
    fun findFirstByCompanyKey(companyKey: String): ApplicationEntity?

    /**
     * Insert only if this job has never been applied. Returns false when the
     * row already existed.
     *
     * This is the hard double-apply guard. Kafka is at-least-once, so a
     * redelivered message reaches the worker again; the dedupe re-check in the
     * consumer is the cheap first line of defence and this is the one that
     * cannot be raced or forgotten. Note that it deliberately does NOT use
     * MongoRepository.save(): with a non-null id that performs an upsert-
     * replace, which would overwrite the first outcome with a later one.
     */
    fun insertIfAbsent(application: ApplicationEntity): Boolean
}