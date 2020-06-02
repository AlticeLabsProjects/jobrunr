package org.jobrunr.scheduling;

import org.jobrunr.configuration.JobRunr;
import org.jobrunr.jobs.Job;
import org.jobrunr.jobs.JobContext;
import org.jobrunr.jobs.states.ProcessingState;
import org.jobrunr.scheduling.cron.Cron;
import org.jobrunr.server.BackgroundJobServer;
import org.jobrunr.storage.PageRequest;
import org.jobrunr.storage.SimpleStorageProvider;
import org.jobrunr.stubs.TestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.time.Duration.ofSeconds;
import static java.time.Instant.now;
import static java.time.ZoneId.systemDefault;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;
import static org.awaitility.Durations.FIVE_SECONDS;
import static org.awaitility.Durations.ONE_MINUTE;
import static org.awaitility.Durations.TEN_SECONDS;
import static org.jobrunr.JobRunrAssertions.assertThat;
import static org.jobrunr.jobs.JobTestBuilder.anEnqueuedJob;
import static org.jobrunr.jobs.states.StateName.ENQUEUED;
import static org.jobrunr.jobs.states.StateName.FAILED;
import static org.jobrunr.jobs.states.StateName.PROCESSING;
import static org.jobrunr.jobs.states.StateName.SCHEDULED;
import static org.jobrunr.jobs.states.StateName.SUCCEEDED;
import static org.jobrunr.server.BackgroundJobServerConfiguration.usingStandardConfiguration;

public class BackgroundJobTest {

    private TestService testService;
    private SimpleStorageProvider jobStorageProvider;
    private BackgroundJobServer backgroundJobServer;

    @BeforeEach
    void setUpTests() throws IOException {
        Files.deleteIfExists(Paths.get("/tmp/code.txt"));

        testService = new TestService();
        testService.reset();
        jobStorageProvider = new SimpleStorageProvider();
        backgroundJobServer = new BackgroundJobServer(jobStorageProvider, null, usingStandardConfiguration().andPollIntervalInSeconds(5));
        JobRunr.configure()
                .useStorageProvider(jobStorageProvider)
                .useBackgroundJobServer(backgroundJobServer)
                .initialize();

        backgroundJobServer.start();
    }

    @AfterEach
    public void cleanUp() {
        backgroundJobServer.stop();
    }

    @Test
    void testEnqueueSystemOut() {
        JobId jobId = BackgroundJob.enqueue(() -> System.out.println("this is a test"));
        await().atMost(FIVE_SECONDS).until(() -> jobStorageProvider.getJobById(jobId).getState() == SUCCEEDED);
        assertThat(jobStorageProvider.getJobById(jobId)).hasStates(ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testEnqueue() {
        JobId jobId = BackgroundJob.enqueue(() -> testService.doWork());
        await().atMost(FIVE_SECONDS).until(() -> jobStorageProvider.getJobById(jobId).getState() == SUCCEEDED);
        assertThat(jobStorageProvider.getJobById(jobId)).hasStates(ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testEnqueueWithCustomObject() {
        final TestService.Work work = new TestService.Work(2, "some string", UUID.randomUUID());
        JobId jobId = BackgroundJob.enqueue(() -> testService.doWork(work));
        await().atMost(FIVE_SECONDS).until(() -> jobStorageProvider.getJobById(jobId).getState() == SUCCEEDED);
        assertThat(jobStorageProvider.getJobById(jobId)).hasStates(ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testEnqueueWithPath() {
        JobId jobId = BackgroundJob.enqueue(() -> testService.doWorkWithPath(Path.of("/tmp/jobrunr/example.log")));
        await().atMost(FIVE_SECONDS).until(() -> jobStorageProvider.getJobById(jobId).getState() == SUCCEEDED);
        assertThat(jobStorageProvider.getJobById(jobId)).hasStates(ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testEnqueueWithJobContextAndMetadata() {
        JobId jobId = BackgroundJob.enqueue(() -> testService.doWork(5, JobContext.Null));
        await().atMost(FIVE_SECONDS).until(() -> jobStorageProvider.getJobById(jobId).getState() == SUCCEEDED);
        Job jobById = jobStorageProvider.getJobById(jobId);
        assertThat(jobById)
                .hasStates(ENQUEUED, PROCESSING, SUCCEEDED)
                .hasMetadata("test", "test");
    }

    @Test
    void testEnqueueStreamWithMultipleParameters() {
        Stream<UUID> workStream = getWorkStream();
        AtomicInteger atomicInteger = new AtomicInteger();
        BackgroundJob.enqueue(workStream, (uuid) -> testService.doWork(uuid.toString(), atomicInteger.incrementAndGet(), now()));

        await().atMost(FIVE_SECONDS).untilAsserted(() -> assertThat(jobStorageProvider.countJobs(SUCCEEDED)).isEqualTo(5));
    }

    @Test
    void testEnqueueStreamWithWrappingObjectAsParameter() {
        AtomicInteger atomicInteger = new AtomicInteger();
        Stream<TestService.Work> workStream = getWorkStream()
                .map(uuid -> new TestService.Work(atomicInteger.incrementAndGet(), "some string " + uuid, uuid));

        BackgroundJob.enqueue(workStream, (work) -> testService.doWork(work));
        await().atMost(FIVE_SECONDS).untilAsserted(() -> assertThat(jobStorageProvider.countJobs(SUCCEEDED)).isEqualTo(5));
    }

    @Test
    void testEnqueueStreamWithParameterFromWrappingObject() {
        AtomicInteger atomicInteger = new AtomicInteger();
        Stream<TestService.Work> workStream = getWorkStream()
                .map(uuid -> new TestService.Work(atomicInteger.incrementAndGet(), "some string " + uuid, uuid));

        BackgroundJob.enqueue(workStream, (work) -> testService.doWork(work.getUuid()));
        await().atMost(FIVE_SECONDS).untilAsserted(() -> assertThat(jobStorageProvider.countJobs(SUCCEEDED)).isEqualTo(5));
    }

    @Test
    void testFailedJobAddsFailedStateAndScheduledThanksToDefaultRetryFilter() {
        JobId jobId = BackgroundJob.enqueue(() -> testService.doWorkThatFails());
        await().atMost(FIVE_SECONDS).untilAsserted(() -> assertThat(jobStorageProvider.getJobById(jobId)).hasStates(ENQUEUED, PROCESSING, FAILED, SCHEDULED));
    }

    @Test
    void testScheduleWithZonedDateTime() {
        JobId jobId = BackgroundJob.schedule(() -> testService.doWork(), ZonedDateTime.now().plusSeconds(7));
        await().during(FIVE_SECONDS).until(() -> jobStorageProvider.getJobById(jobId).getState() == SCHEDULED);
        await().atMost(TEN_SECONDS).until(() -> jobStorageProvider.getJobById(jobId).getState() == SUCCEEDED);
        assertThat(jobStorageProvider.getJobById(jobId)).hasStates(SCHEDULED, ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testScheduleWithOffsetDateTime() {
        JobId jobId = BackgroundJob.schedule(() -> testService.doWork(), OffsetDateTime.now().plusSeconds(7));
        await().during(FIVE_SECONDS).until(() -> jobStorageProvider.getJobById(jobId).getState() == SCHEDULED);
        await().atMost(TEN_SECONDS).until(() -> jobStorageProvider.getJobById(jobId).getState() == SUCCEEDED);
        assertThat(jobStorageProvider.getJobById(jobId)).hasStates(SCHEDULED, ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testScheduleWithLocalDateTime() {
        JobId jobId = BackgroundJob.schedule(() -> testService.doWork(), LocalDateTime.now().plusSeconds(7));
        await().during(FIVE_SECONDS).until(() -> jobStorageProvider.getJobById(jobId).getState() == SCHEDULED);
        await().atMost(TEN_SECONDS).until(() -> jobStorageProvider.getJobById(jobId).getState() == SUCCEEDED);
        assertThat(jobStorageProvider.getJobById(jobId)).hasStates(SCHEDULED, ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testScheduleWithInstant() {
        JobId jobId = BackgroundJob.schedule(() -> testService.doWork(), now().plusSeconds(7));
        await().during(FIVE_SECONDS).until(() -> jobStorageProvider.getJobById(jobId).getState() == SCHEDULED);
        await().atMost(TEN_SECONDS).until(() -> jobStorageProvider.getJobById(jobId).getState() == SUCCEEDED);
        assertThat(jobStorageProvider.getJobById(jobId)).hasStates(SCHEDULED, ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testScheduleUsingDateTimeInTheFutureIsNotEnqueued() {
        JobId jobId = BackgroundJob.schedule(() -> testService.doWork(), now().plus(100, ChronoUnit.DAYS));
        await().during(FIVE_SECONDS).until(() -> jobStorageProvider.getJobById(jobId).getState() == SCHEDULED);
        await().atMost(FIVE_SECONDS).until(() -> jobStorageProvider.getJobById(jobId).getState() == SCHEDULED);
        assertThat(jobStorageProvider.getJobById(jobId)).hasStates(SCHEDULED);
    }

    @Test
    void testScheduleThatSchedulesOtherJobs() {
        JobId jobId = BackgroundJob.schedule(() -> testService.scheduleNewWork(5), now().plusSeconds(1));
        await().atMost(ONE_MINUTE).until(() -> jobStorageProvider.countJobs(SUCCEEDED) == (5 + 1));
        assertThat(jobStorageProvider.getJobById(jobId)).hasStates(SCHEDULED, ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testScheduleThatSchedulesOtherJobsSlowlyDoesNotBlockOtherWorkers() {
        JobId jobId = BackgroundJob.schedule(() -> testService.scheduleNewWorkSlowly(5), now().plusSeconds(1));
        await().atMost(ofSeconds(12)).until(() -> (jobStorageProvider.countJobs(PROCESSING) + jobStorageProvider.countJobs(SUCCEEDED)) > 1);
        assertThat(jobStorageProvider.getJobById(jobId)).hasStates(SCHEDULED, ENQUEUED, PROCESSING);
    }

    @Test
    void testRecurringJob() {
        BackgroundJob.scheduleRecurringly(() -> testService.doWork(5), Cron.minutely());
        await().atMost(65, SECONDS).until(() -> jobStorageProvider.countJobs(SUCCEEDED) == 1);

        final Job job = jobStorageProvider.getJobs(SUCCEEDED, PageRequest.asc(0, 1)).get(0);
        assertThat(jobStorageProvider.getJobById(job.getId())).hasStates(SCHEDULED, ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testRecurringJobWithId() {
        BackgroundJob.scheduleRecurringly("theId", () -> testService.doWork(5), Cron.minutely());
        await().atMost(65, SECONDS).until(() -> jobStorageProvider.countJobs(SUCCEEDED) == 1);

        final Job job = jobStorageProvider.getJobs(SUCCEEDED, PageRequest.asc(0, 1)).get(0);
        assertThat(jobStorageProvider.getJobById(job.getId())).hasStates(SCHEDULED, ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testRecurringJobWithIdAndTimezone() {
        BackgroundJob.scheduleRecurringly("theId", () -> testService.doWork(5), Cron.minutely(), systemDefault());
        await().atMost(65, SECONDS).until(() -> jobStorageProvider.countJobs(SUCCEEDED) == 1);

        final Job job = jobStorageProvider.getJobs(SUCCEEDED, PageRequest.asc(0, 1)).get(0);
        assertThat(jobStorageProvider.getJobById(job.getId())).hasStates(SCHEDULED, ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testDeleteOfRecurringJob() {
        String jobId = BackgroundJob.scheduleRecurringly(() -> testService.doWork(5), Cron.minutely());
        BackgroundJob.deleteRecurringly(jobId);
        await().atMost(61, SECONDS).until(() -> jobStorageProvider.countJobs(ENQUEUED) == 0 && jobStorageProvider.countJobs(SUCCEEDED) == 0);
        assertThat(jobStorageProvider.getRecurringJobs()).isEmpty();
    }

    @Test
    void testStopBackgroundJobServerWhileProcessing() {
        final JobId jobId = BackgroundJob.enqueue(() -> testService.doWorkThatTakesLong(15));
        await().atMost(6, SECONDS).until(() -> jobStorageProvider.getJobById(jobId).hasState(PROCESSING));
        backgroundJobServer.stop();
        await().atMost(5, SECONDS).until(() -> jobStorageProvider.getJobById(jobId).hasState(FAILED) || jobStorageProvider.getJobById(jobId).hasState(SCHEDULED));
        backgroundJobServer.start();
        await().atMost(21, SECONDS).until(() -> jobStorageProvider.getJobById(jobId).hasState(SUCCEEDED));
    }

    @Test
    void testBackgroundJobServerWasKilledWhileProcessing() {
        final Job jobThatWasProcessedButBackgroundJobServerWasKilled = jobStorageProvider.save(anEnqueuedJob().withState(new ProcessingState(backgroundJobServer.getId()), now().minus(2, ChronoUnit.MINUTES)).build());
        await().atMost(7, SECONDS).untilAsserted(() -> assertThat(jobStorageProvider.getJobById(jobThatWasProcessedButBackgroundJobServerWasKilled.getId())).hasStates(ENQUEUED, PROCESSING, FAILED, SCHEDULED));
        await().atMost(7, SECONDS).until(() -> jobStorageProvider.getJobById(jobThatWasProcessedButBackgroundJobServerWasKilled.getId()).hasState(SUCCEEDED));
    }

    @Test
    void testHearbeatsAreSentForJobsInProcessingState() {
        final JobId jobId = BackgroundJob.enqueue(() -> testService.doWorkThatTakesLong(16));
        await().pollInterval(150, MILLISECONDS).pollDelay(3, SECONDS).atMost(7, SECONDS).untilAsserted(() -> assertThat(jobStorageProvider.getJobById(jobId)).hasUpdatedAtCloseTo(now(), within(500, ChronoUnit.MILLIS)));
        await().pollInterval(150, MILLISECONDS).pollDelay(3, SECONDS).atMost(7, SECONDS).untilAsserted(() -> assertThat(jobStorageProvider.getJobById(jobId)).hasUpdatedAtCloseTo(now(), within(500, ChronoUnit.MILLIS)));
        await().pollInterval(150, MILLISECONDS).pollDelay(3, SECONDS).atMost(7, SECONDS).untilAsserted(() -> assertThat(jobStorageProvider.getJobById(jobId)).hasUpdatedAtCloseTo(now(), within(500, ChronoUnit.MILLIS)));
    }

    private Stream<UUID> getWorkStream() {
        return IntStream.range(0, 5)
                .mapToObj(i -> UUID.randomUUID());
    }
}
