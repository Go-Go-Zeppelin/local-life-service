package com.hmdp;

import com.hmdp.action.BlogStateAction;
import com.hmdp.config.BlogStateMachineConfig;
import com.hmdp.dto.blog.BlogDraftCommand;
import com.hmdp.dto.blog.BlogReviewCommand;
import com.hmdp.entity.Blog;
import com.hmdp.enums.BlogState;
import com.hmdp.enums.BlogStateEvent;
import com.hmdp.handler.BlogStateHandler;
import com.hmdp.handler.BlogTransitionContext;
import com.hmdp.mapper.BlogFeedOutboxMapper;
import com.hmdp.mapper.BlogMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.Lifecycle;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.persist.StateMachinePersister;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用真实状态机及模拟 Mapper；数据库事务由 BlogPublishIntegrationTest 验证。 */
@SpringJUnitConfig(BlogStateMachineTest.TestConfig.class)
class BlogStateMachineTest {
    @TestConfiguration
    @Import({BlogStateMachineConfig.class, BlogStateAction.class, BlogStateHandler.class})
    static class TestConfig {
        @Bean BlogMapper blogMapper() { return mock(BlogMapper.class); }
        @Bean BlogFeedOutboxMapper outboxMapper() { return mock(BlogFeedOutboxMapper.class); }
    }

    @Autowired private BlogMapper blogMapper;
    @Autowired private BlogFeedOutboxMapper outboxMapper;
    @Autowired private BlogStateHandler handler;
    @Autowired private StateMachineFactory<BlogState, BlogStateEvent> factory;
    @Autowired private StateMachinePersister<BlogState, BlogStateEvent, Blog> persister;

    @BeforeEach
    void setup() {
        reset(blogMapper, outboxMapper);
        when(blogMapper.editIfUnchanged(any(), any())).thenReturn(1);
        when(blogMapper.submitIfUnchanged(any())).thenReturn(1);
        when(blogMapper.approveIfUnchanged(any(), anyLong(), any())).thenReturn(1);
        when(blogMapper.rejectIfUnchanged(any(), anyLong(), any(), anyString())).thenReturn(1);
        when(outboxMapper.insert(any())).thenReturn(1);
        // 这里只模拟 Handler 的入口约束，集成测试会检查真实事务及回滚结果。
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void cleanup() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private BlogTransitionContext context(BlogState state) {
        Blog blog = new Blog();
        blog.setId(100L);
        blog.setUserId(10L);
        blog.setStatus(state.getCode());
        blog.setVersion(1L);
        blog.setReviewRound(1);
        blog.setTitle("笔记标题");
        blog.setContent("笔记正文");
        BlogTransitionContext context = new BlogTransitionContext(blog, 1L);
        context.setDraftCommand(new BlogDraftCommand());
        BlogReviewCommand review = new BlogReviewCommand();
        review.setReason("请补充正文");
        context.setReviewCommand(review);
        return context;
    }

    @ParameterizedTest
    @CsvSource({
            "DRAFT, EDIT, DRAFT",
            "REJECTED, EDIT, DRAFT",
            "DRAFT, SUBMIT, PENDING_REVIEW",
            "REJECTED, SUBMIT, PENDING_REVIEW",
            "PENDING_REVIEW, APPROVE, PUBLISHED",
            "PENDING_REVIEW, REJECT, REJECTED"
    })
    void configuredTransitionsRestoreStateAndRunTheBoundAction(
            BlogState source, BlogStateEvent event, BlogState target) throws Exception {
        BlogTransitionContext context = context(source);
        StateMachine<BlogState, BlogStateEvent> machine = factory.getStateMachine();
        try {
            persister.restore(machine, context.getBlog());
            assertTrue(((Lifecycle) machine).isRunning());
            assertEquals(source, machine.getState().getId());
            boolean accepted = machine.sendEvent(MessageBuilder.withPayload(event)
                    .setHeader(BlogTransitionContext.HEADER, context).build());
            assertTrue(accepted);
            assertTrue(context.isActionCompleted());
            assertNull(context.getFailure());
            assertEquals(target, machine.getState().getId());
        } finally {
            machine.stop();
        }
    }

    @ParameterizedTest
    @CsvSource({"DRAFT, APPROVE", "DRAFT, REJECT", "PENDING_REVIEW, EDIT", "PENDING_REVIEW, SUBMIT",
            "PUBLISHED, EDIT", "PUBLISHED, SUBMIT", "PUBLISHED, APPROVE", "PUBLISHED, REJECT", "REJECTED, REJECT"})
    void illegalEventsAreRejectedWithoutDatabaseActions(BlogState source, BlogStateEvent event) {
        BlogTransitionContext context = context(source);
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> handler.handle(event, context));
        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertFalse(context.isActionCompleted());
        verifyNoInteractions(blogMapper, outboxMapper);
    }

    @Test
    void factoryInstancesAndTheirRestoredStatesAreIndependent() throws Exception {
        StateMachine<BlogState, BlogStateEvent> first = factory.getStateMachine();
        StateMachine<BlogState, BlogStateEvent> second = factory.getStateMachine();
        assertNotSame(first, second);
        BlogTransitionContext firstContext = context(BlogState.PENDING_REVIEW);
        BlogTransitionContext secondContext = context(BlogState.DRAFT);
        try {
            persister.restore(first, firstContext.getBlog());
            persister.restore(second, secondContext.getBlog());
            first.sendEvent(MessageBuilder.withPayload(BlogStateEvent.APPROVE)
                    .setHeader(BlogTransitionContext.HEADER, firstContext).build());
            assertEquals(BlogState.PUBLISHED, first.getState().getId());
            assertEquals(BlogState.DRAFT, second.getState().getId());
            assertFalse(secondContext.isActionCompleted());
            assertNull(secondContext.getFailure());
        } finally {
            first.stop();
            second.stop();
        }
    }

    @ParameterizedTest
    @CsvSource({"DRAFT, EDIT", "DRAFT, SUBMIT", "PENDING_REVIEW, APPROVE", "PENDING_REVIEW, REJECT"})
    void everyActionFailurePreservesTheOriginalException(BlogState source, BlogStateEvent event) {
        IllegalStateException failure = new IllegalStateException("database unavailable");
        switch (event) {
            case EDIT:
                when(blogMapper.editIfUnchanged(any(), any())).thenThrow(failure);
                break;
            case SUBMIT:
                when(blogMapper.submitIfUnchanged(any())).thenThrow(failure);
                break;
            case APPROVE:
                when(outboxMapper.insert(any())).thenThrow(failure);
                break;
            case REJECT:
                when(blogMapper.rejectIfUnchanged(any(), anyLong(), any(), anyString())).thenThrow(failure);
                break;
            default:
                throw new IllegalArgumentException("未配置失败场景: " + event);
        }
        BlogTransitionContext context = context(source);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> handler.handle(event, context)));
        assertSame(failure, context.getFailure());
        assertFalse(context.isActionCompleted());
    }

    @Test
    void sendEventCanAcceptAnActionThatFailed() throws Exception {
        IllegalStateException failure = new IllegalStateException("outbox unavailable");
        when(outboxMapper.insert(any())).thenThrow(failure);
        BlogTransitionContext context = context(BlogState.PENDING_REVIEW);
        StateMachine<BlogState, BlogStateEvent> machine = factory.getStateMachine();
        try {
            persister.restore(machine, context.getBlog());
            boolean accepted = machine.sendEvent(MessageBuilder.withPayload(BlogStateEvent.APPROVE)
                    .setHeader(BlogTransitionContext.HEADER, context).build());
            assertTrue(accepted);
            assertSame(failure, context.getFailure());
            assertFalse(context.isActionCompleted());
        } finally {
            machine.stop();
        }
    }

    @Test
    void zeroRowUpdatesPropagateConflictAndNeverComplete() {
        when(blogMapper.approveIfUnchanged(any(), anyLong(), any())).thenReturn(0);
        BlogTransitionContext context = context(BlogState.PENDING_REVIEW);
        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> handler.handle(BlogStateEvent.APPROVE, context));
        assertEquals(HttpStatus.CONFLICT, failure.getStatus());
        assertFalse(context.isActionCompleted());
        verifyNoInteractions(outboxMapper);
    }

    @Test
    void zeroRowOutboxInsertNeverMarksActionComplete() {
        when(outboxMapper.insert(any())).thenReturn(0);
        BlogTransitionContext context = context(BlogState.PENDING_REVIEW);
        assertThrows(IllegalStateException.class, () -> handler.handle(BlogStateEvent.APPROVE, context));
        assertFalse(context.isActionCompleted());
    }

    @Test
    void synchronousExecutionPreservesTheCallingThread() {
        Thread caller = Thread.currentThread();
        when(blogMapper.submitIfUnchanged(any())).thenAnswer(invocation -> {
            assertSame(caller, Thread.currentThread());
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return 1;
        });
        BlogTransitionContext context = context(BlogState.DRAFT);
        handler.handle(BlogStateEvent.SUBMIT, context);
        assertTrue(context.isActionCompleted());
    }

    @Test
    void handlerRequiresTransactionAndInvalidStoredStatesFailExplicitly() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThrows(IllegalStateException.class,
                () -> handler.handle(BlogStateEvent.EDIT, context(BlogState.DRAFT)));
        assertThrows(IllegalStateException.class, () -> BlogState.fromCode(null));
        assertThrows(IllegalStateException.class, () -> BlogState.fromCode(99));
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptedEventWithoutCompletedActionFailsAndStopsMachine() throws Exception {
        StateMachine<BlogState, BlogStateEvent> machine = mock(StateMachine.class);
        StateMachineFactory<BlogState, BlogStateEvent> factory = mock(StateMachineFactory.class);
        StateMachinePersister<BlogState, BlogStateEvent, Blog> persister = mock(StateMachinePersister.class);
        when(factory.getStateMachine()).thenReturn(machine);
        when(machine.sendEvent(any(org.springframework.messaging.Message.class))).thenReturn(true);
        BlogStateHandler isolated = new BlogStateHandler();
        ReflectionTestUtils.setField(isolated, "stateMachineFactory", factory);
        ReflectionTestUtils.setField(isolated, "persister", persister);

        BlogTransitionContext context = context(BlogState.DRAFT);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> isolated.handle(BlogStateEvent.EDIT, context));
        assertEquals("笔记状态转换动作未完成", failure.getMessage());
        verify(machine).stop();

        Exception restoreFailure = new Exception("restore failed");
        doThrow(restoreFailure).when(persister).restore(machine, context.getBlog());
        assertSame(restoreFailure, assertThrows(IllegalStateException.class,
                () -> isolated.handle(BlogStateEvent.EDIT, context)).getCause());
        verify(machine, times(2)).stop();
    }
}
