package dev.mstaszew.campaign.common.repo

import dev.mstaszew.campaign.common.domain.ApplyTaskEntity
import dev.mstaszew.campaign.common.domain.JobListingEntity
import dev.mstaszew.campaign.common.domain.TaskState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = ["spring.jpa.hibernate.ddl-auto=validate"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ApplyTaskRepositoryIT {

    @Autowired
    lateinit var tasks: ApplyTaskRepository

    @Autowired
    lateinit var listings: JobListingRepository

    @Autowired
    lateinit var em: TestEntityManager

    @Autowired
    lateinit var tx: TransactionTemplate

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) {
            val c = PostgresContainerSupport.container
            registry.add("spring.datasource.url", c::getJdbcUrl)
            registry.add("spring.datasource.username", c::getUsername)
            registry.add("spring.datasource.password", c::getPassword)
        }
    }

    private fun newListing(n: Int): JobListingEntity =
        listings.save(
            JobListingEntity(
                source = "nofluffjobs",
                sourceJobId = "job-$n",
                company = "Acme $n Ltd",
                companyKey = "acme-$n",
                roleTitle = "Java Developer",
                url = "https://example.com/job-$n",
            ),
        )

    /** Clear the persistence context so native RETURNING rows are not shadowed by managed copies. */
    private fun resetContext() {
        em.flush()
        em.clear()
    }

    @Test
    fun `claim returns highest priority task and marks it claimed`() {
        val low = newListing(1)
        val high = newListing(2)
        tasks.save(ApplyTaskEntity(listingId = low.id, state = TaskState.QUEUED, priority = 0))
        tasks.save(ApplyTaskEntity(listingId = high.id, state = TaskState.QUEUED, priority = 5))
        resetContext()

        val claimed = tx.execute { tasks.claim("worker-1", 600) }

        assertThat(claimed).isNotNull
        claimed!!
        assertThat(claimed.listingId).isEqualTo(high.id)
        assertThat(claimed.state).isEqualTo(TaskState.CLAIMED)
        assertThat(claimed.claimedBy).isEqualTo("worker-1")
        assertThat(claimed.attempts).isEqualTo(1)
        assertThat(claimed.claimExpiresAt).isAfter(Instant.now())
    }

    @Test
    fun `claim returns null when queue is empty`() {
        resetContext()
        assertThat(tx.execute { tasks.claim("worker-1", 600) }).isNull()
    }

    @Test
    fun `claim skips tasks that are not queued`() {
        val listing = newListing(3)
        tasks.save(ApplyTaskEntity(listingId = listing.id, state = TaskState.SUBMITTED))
        resetContext()

        assertThat(tx.execute { tasks.claim("worker-1", 600) }).isNull()
    }

    @Test
    fun `reaper requeues expired claims under max attempts and kills exhausted ones`() {
        val requeue = newListing(4)
        val dead = newListing(5)
        tasks.save(
            ApplyTaskEntity(
                listingId = requeue.id,
                state = TaskState.CLAIMED,
                claimedBy = "ghost",
                attempts = 1,
                maxAttempts = 3,
                claimExpiresAt = Instant.now().minusSeconds(60),
            ),
        )
        tasks.save(
            ApplyTaskEntity(
                listingId = dead.id,
                state = TaskState.CLAIMED,
                claimedBy = "ghost",
                attempts = 3,
                maxAttempts = 3,
                claimExpiresAt = Instant.now().minusSeconds(60),
            ),
        )
        resetContext()

        val released = tx.execute { tasks.releaseExpiredClaims() }

        assertThat(released).isEqualTo(2)
        val requeued = tasks.findByStateOrderByPriorityDescIdAsc(TaskState.QUEUED)
            .first { it.listingId == requeue.id }
        assertThat(requeued.state).isEqualTo(TaskState.QUEUED)
        assertThat(requeued.claimedBy).isNull()
        val killed = tasks.findByStateOrderByPriorityDescIdAsc(TaskState.DEAD)
            .first { it.listingId == dead.id }
        assertThat(killed.state).isEqualTo(TaskState.DEAD)
    }

    @Test
    fun `only one active task per listing is allowed`() {
        val listing = newListing(6)
        tasks.save(ApplyTaskEntity(listingId = listing.id, state = TaskState.QUEUED))
        resetContext()

        val second = ApplyTaskEntity(listingId = listing.id, state = TaskState.QUEUED)
        org.junit.jupiter.api.Assertions.assertThrows(DataIntegrityViolationException::class.java) {
            tasks.saveAndFlush(second)
        }
    }
}
