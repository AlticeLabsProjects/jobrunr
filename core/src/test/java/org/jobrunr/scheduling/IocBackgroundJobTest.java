package org.jobrunr.scheduling;

import org.jobrunr.configuration.JobRunr;
import org.jobrunr.jobs.Job;
import org.jobrunr.jobs.JobContext;
import org.jobrunr.jobs.stubs.SimpleJobActivator;
import org.jobrunr.scheduling.cron.Cron;
import org.jobrunr.server.BackgroundJobServer;
import org.jobrunr.storage.PageRequest;
import org.jobrunr.storage.SimpleStorageProvider;
import org.jobrunr.stubs.TestService;
import org.jobrunr.stubs.TestService.Work;
import org.jobrunr.stubs.TestServiceForIoC;
import org.jobrunr.stubs.TestServiceInterface;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
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
import static org.awaitility.Awaitility.await;
import static org.awaitility.Durations.FIVE_SECONDS;
import static org.awaitility.Durations.ONE_MINUTE;
import static org.awaitility.Durations.TEN_SECONDS;
import static org.jobrunr.JobRunrAssertions.assertThat;
import static org.jobrunr.jobs.states.StateName.ENQUEUED;
import static org.jobrunr.jobs.states.StateName.FAILED;
import static org.jobrunr.jobs.states.StateName.PROCESSING;
import static org.jobrunr.jobs.states.StateName.SCHEDULED;
import static org.jobrunr.jobs.states.StateName.SUCCEEDED;
import static org.jobrunr.server.BackgroundJobServerConfiguration.usingStandardConfiguration;

public class IocBackgroundJobTest {

    private SimpleStorageProvider jobStorageProvider;
    private BackgroundJobServer backgroundJobServer;
    private TestServiceForIoC testServiceForIoC;
    private TestServiceInterface testServiceInterface;

    @BeforeEach
    public void setUpTests() {
        jobStorageProvider = new SimpleStorageProvider();
        testServiceForIoC = new TestServiceForIoC("a constructor arg");
        testServiceInterface = testServiceForIoC;
        SimpleJobActivator jobActivator = new SimpleJobActivator(testServiceForIoC, new TestService());
        backgroundJobServer = new BackgroundJobServer(jobStorageProvider, jobActivator, usingStandardConfiguration().andPollIntervalInSeconds(5));
        JobRunr.configure()
                .useStorageProvider(jobStorageProvider)
                .useJobActivator(jobActivator)
                .useBackgroundJobServer(backgroundJobServer)
                .initialize();
        backgroundJobServer.start();
    }

    @AfterEach
    public void cleanUp() {
        backgroundJobServer.stop();
    }

    @Test
    void testEnqueue() {
        JobId jobId = BackgroundJob.<TestService>enqueue(x -> x.doWork());
        await().atMost(FIVE_SECONDS).until(() -> jobStorageProvider.getJobById(jobId).getState() == SUCCEEDED);
        assertThat(jobStorageProvider.getJobById(jobId)).hasStates(ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testEnqueueWithMethodReference() {
        JobId jobId = BackgroundJob.<TestService>enqueue(TestService::doWork);
        await().atMost(FIVE_SECONDS).until(() -> jobStorageProvider.getJobById(jobId).getState() == SUCCEEDED);
        assertThat(jobStorageProvider.getJobById(jobId)).hasStates(ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testEnqueueUsingServiceInstance() {
        JobId jobId = BackgroundJob.enqueue(() -> testServiceForIoC.doWork());
        await().atMost(FIVE_SECONDS).until(() -> jobStorageProvider.getJobById(jobId).getState() == SUCCEEDED);
        assertThat(jobStorageProvider.getJobById(jobId)).hasStates(ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testEnqueueUsingServiceInterfaceInstance() {
        JobId jobId = BackgroundJob.enqueue(() -> testServiceInterface.doWork());
        await().atMost(FIVE_SECONDS).until(() -> jobStorageProvider.getJobById(jobId).getState() == SUCCEEDED);
        assertThat(jobStorageProvider.getJobById(jobId)).hasStates(ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testEnqueueWithCustomObject() {
        final TestService.Work work = new TestService.Work(2, "some string", UUID.randomUUID());
        JobId jobId = BackgroundJob.<TestService>enqueue(x -> x.doWork(work));
        await().atMost(FIVE_SECONDS).until(() -> jobStorageProvider.getJobById(jobId).getState() == SUCCEEDED);
        assertThat(jobStorageProvider.getJobById(jobId)).hasStates(ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testEnqueueWithPath() {
        JobId jobId = BackgroundJob.<TestService>enqueue(x -> x.doWorkWithPath(Path.of("/tmp/jobrunr/example.log")));
        await().atMost(FIVE_SECONDS).until(() -> jobStorageProvider.getJobById(jobId).getState() == SUCCEEDED);
        assertThat(jobStorageProvider.getJobById(jobId)).hasStates(ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testEnqueueWithJobContextAndMetadata() {
        JobId jobId = BackgroundJob.<TestService>enqueue(x -> x.doWork(5, JobContext.Null));
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
        BackgroundJob.<TestService, UUID>enqueue(workStream, (x, uuid) -> x.doWork(uuid.toString(), atomicInteger.incrementAndGet(), now()));

        await().atMost(FIVE_SECONDS).untilAsserted(() -> assertThat(jobStorageProvider.countJobs(SUCCEEDED)).isEqualTo(5));
    }

    @Test
    void testEnqueueStreamWithWrappingObjectAsParameter() {
        AtomicInteger atomicInteger = new AtomicInteger();
        Stream<Work> workStream = getWorkStream()
                .map(uuid -> new Work(atomicInteger.incrementAndGet(), "some string " + uuid, uuid));

        BackgroundJob.<TestService, Work>enqueue(workStream, (x, work) -> x.doWork(work));
        await().atMost(FIVE_SECONDS).untilAsserted(() -> assertThat(jobStorageProvider.countJobs(SUCCEEDED)).isEqualTo(5));
    }

    @Test
    void testEnqueueStreamWithParameterFromWrappingObject() {
        AtomicInteger atomicInteger = new AtomicInteger();
        Stream<TestService.Work> workStream = getWorkStream()
                .map(uuid -> new TestService.Work(atomicInteger.incrementAndGet(), "some string " + uuid, uuid));

        BackgroundJob.<TestService, Work>enqueue(workStream, (x, work) -> x.doWork(work.getUuid()));
        await().atMost(FIVE_SECONDS).untilAsserted(() -> assertThat(jobStorageProvider.countJobs(SUCCEEDED)).isEqualTo(5));
    }

    @Test
    void testFailedJobAddsFailedStateAndScheduledThanksToDefaultRetryFilter() {
        JobId jobId = BackgroundJob.<TestService>enqueue(x -> x.doWorkThatFails());
        await().atMost(FIVE_SECONDS).untilAsserted(() -> assertThat(jobStorageProvider.getJobById(jobId)).hasStates(ENQUEUED, PROCESSING, FAILED, SCHEDULED));
    }

    @Test
    void testScheduleWithZonedDateTime() {
        JobId jobId = BackgroundJob.<TestService>schedule(x -> x.doWork(), ZonedDateTime.now().plusSeconds(7));
        await().during(FIVE_SECONDS).until(() -> jobStorageProvider.getJobById(jobId).getState() == SCHEDULED);
        await().atMost(TEN_SECONDS).until(() -> jobStorageProvider.getJobById(jobId).getState() == SUCCEEDED);
        assertThat(jobStorageProvider.getJobById(jobId)).hasStates(SCHEDULED, ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testScheduleWithOffsetDateTime() {
        JobId jobId = BackgroundJob.<TestService>schedule(x -> x.doWork(), OffsetDateTime.now().plusSeconds(7));
        await().during(FIVE_SECONDS).until(() -> jobStorageProvider.getJobById(jobId).getState() == SCHEDULED);
        await().atMost(TEN_SECONDS).until(() -> jobStorageProvider.getJobById(jobId).getState() == SUCCEEDED);
        assertThat(jobStorageProvider.getJobById(jobId)).hasStates(SCHEDULED, ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testScheduleWithLocalDateTime() {
        JobId jobId = BackgroundJob.<TestService>schedule(x -> x.doWork(), LocalDateTime.now().plusSeconds(7));
        await().during(FIVE_SECONDS).until(() -> jobStorageProvider.getJobById(jobId).getState() == SCHEDULED);
        await().atMost(TEN_SECONDS).until(() -> jobStorageProvider.getJobById(jobId).getState() == SUCCEEDED);
        assertThat(jobStorageProvider.getJobById(jobId)).hasStates(SCHEDULED, ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testScheduleWithInstant() {
        JobId jobId = BackgroundJob.<TestService>schedule(x -> x.doWork(), now().plusSeconds(7));
        await().during(FIVE_SECONDS).until(() -> jobStorageProvider.getJobById(jobId).getState() == SCHEDULED);
        await().atMost(TEN_SECONDS).until(() -> jobStorageProvider.getJobById(jobId).getState() == SUCCEEDED);
        assertThat(jobStorageProvider.getJobById(jobId)).hasStates(SCHEDULED, ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testScheduleUsingDateTimeInTheFutureIsNotEnqueued() {
        JobId jobId = BackgroundJob.<TestService>schedule(x -> x.doWork(), now().plus(100, ChronoUnit.DAYS));
        await().during(FIVE_SECONDS).until(() -> jobStorageProvider.getJobById(jobId).getState() == SCHEDULED);
        await().atMost(FIVE_SECONDS).until(() -> jobStorageProvider.getJobById(jobId).getState() == SCHEDULED);
        assertThat(jobStorageProvider.getJobById(jobId)).hasStates(SCHEDULED);
    }

    @Test
    void testScheduleThatSchedulesOtherJobs() {
        JobId jobId = BackgroundJob.<TestService>schedule(x -> x.scheduleNewWork(5), now().plusSeconds(1));
        await().atMost(ONE_MINUTE).until(() -> jobStorageProvider.countJobs(SUCCEEDED) == (5 + 1));
        assertThat(jobStorageProvider.getJobById(jobId)).hasStates(SCHEDULED, ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testRecurringJob() {
        BackgroundJob.<TestService>scheduleRecurringly(x -> x.doWork(5), Cron.minutely());
        await().atMost(ofSeconds(65)).until(() -> jobStorageProvider.countJobs(SUCCEEDED) == 1);

        final Job job = jobStorageProvider.getJobs(SUCCEEDED, PageRequest.asc(0, 1)).get(0);
        assertThat(jobStorageProvider.getJobById(job.getId())).hasStates(SCHEDULED, ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testRecurringJobWithId() {
        BackgroundJob.<TestService>scheduleRecurringly("theId", x -> x.doWork(5), Cron.minutely());
        await().atMost(ofSeconds(65)).until(() -> jobStorageProvider.countJobs(SUCCEEDED) == 1);

        final Job job = jobStorageProvider.getJobs(SUCCEEDED, PageRequest.asc(0, 1)).get(0);
        assertThat(jobStorageProvider.getJobById(job.getId())).hasStates(SCHEDULED, ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testRecurringJobWithIdAndTimezone() {
        BackgroundJob.<TestService>scheduleRecurringly("theId", x -> x.doWork(5), Cron.minutely(), systemDefault());
        await().atMost(ofSeconds(65)).until(() -> jobStorageProvider.countJobs(SUCCEEDED) == 1);

        final Job job = jobStorageProvider.getJobs(SUCCEEDED, PageRequest.asc(0, 1)).get(0);
        assertThat(jobStorageProvider.getJobById(job.getId())).hasStates(SCHEDULED, ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testDeleteOfRecurringJob() {
        String jobId = BackgroundJob.<TestService>scheduleRecurringly(x -> x.doWork(5), Cron.minutely());
        BackgroundJob.deleteRecurringly(jobId);
        await().atMost(ofSeconds(61)).until(() -> jobStorageProvider.countJobs(ENQUEUED) == 0 && jobStorageProvider.countJobs(SUCCEEDED) == 0);
        assertThat(jobStorageProvider.getRecurringJobs()).isEmpty();
    }

    private Stream<UUID> getWorkStream() {
        return IntStream.range(0, 5)
                .mapToObj(i -> UUID.randomUUID());
    }
}
