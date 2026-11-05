package com.hmdp.handler;

import com.hmdp.entity.Blog;
import com.hmdp.enums.BlogState;
import com.hmdp.enums.BlogStateEvent;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.persist.StateMachinePersister;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import javax.annotation.Resource;

/** 状态机门面，封装实例创建、恢复、事件发送、执行结果校验和停止。 */
@Component
public class BlogStateHandler {
    @Resource(name = "blogStateMachineFactory")
    private StateMachineFactory<BlogState, BlogStateEvent> stateMachineFactory;
    @Resource(name = "blogStatePersister")
    private StateMachinePersister<BlogState, BlogStateEvent, Blog> persister;

    public void handle(BlogStateEvent event, BlogTransitionContext context) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("笔记状态转换必须在数据库事务内执行");
        }

        StateMachine<BlogState, BlogStateEvent> machine = stateMachineFactory.getStateMachine();
        try {
            // restore 已包含 stop、reset 和 start，从刚锁定的数据库记录恢复当前状态。
            persister.restore(machine, context.getBlog());
            Message<BlogStateEvent> message = MessageBuilder.withPayload(event)
                    .setHeader(BlogTransitionContext.HEADER, context)
                    .build();
            boolean accepted = machine.sendEvent(message);

            // sendEvent 接受事件不等于数据库动作成功，先保留动作抛出的原始异常。
            if (context.getFailure() != null) {
                throw propagate(context.getFailure());
            }
            if (!accepted) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "当前笔记状态不允许执行此操作");
            }
            if (!context.isActionCompleted()) {
                throw new IllegalStateException("笔记状态转换动作未完成");
            }
        } catch (Exception exception) {
            // 恢复过程的受检异常也转换成运行时异常，确保 Facade 事务回滚。
            throw propagate(exception);
        } finally {
            machine.stop();
        }
    }

    private RuntimeException propagate(Exception exception) {
        if (exception instanceof RuntimeException) {
            return (RuntimeException) exception;
        }
        return new IllegalStateException("笔记状态转换失败", exception);
    }
}
