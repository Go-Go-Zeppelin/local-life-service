package com.hmdp.config;

import com.hmdp.entity.Blog;
import com.hmdp.enums.BlogState;
import com.hmdp.enums.BlogStateEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.statemachine.StateMachineContext;
import org.springframework.statemachine.StateMachinePersist;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.StateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineConfigurationConfigurer;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;
import org.springframework.statemachine.persist.DefaultStateMachinePersister;
import org.springframework.statemachine.persist.StateMachinePersister;
import org.springframework.statemachine.support.DefaultStateMachineContext;

import java.util.EnumSet;

/** 声明状态与事件的转换关系，动作由 StatesOnTransition 注解关联。 */
@Configuration
@EnableStateMachineFactory(name = "blogStateMachineFactory")
public class BlogStateMachineConfig extends StateMachineConfigurerAdapter<BlogState, BlogStateEvent> {
    public static final String MACHINE_ID = "blogStateMachine";

    @Override
    public void configure(StateMachineConfigurationConfigurer<BlogState, BlogStateEvent> config) throws Exception {
        // Action 必须与 Facade 在同一线程执行，才能参与同一个数据库事务。
        config.withConfiguration()
                .autoStartup(false)
                .machineId(MACHINE_ID)
                .taskExecutor(new SyncTaskExecutor());
    }

    @Override
    public void configure(StateMachineStateConfigurer<BlogState, BlogStateEvent> states) throws Exception {
        states.withStates()
                .initial(BlogState.DRAFT)
                .states(EnumSet.allOf(BlogState.class));
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<BlogState, BlogStateEvent> transitions) throws Exception {
        transitions
                // 草稿编辑后仍为草稿。
                .withExternal()
                .source(BlogState.DRAFT).target(BlogState.DRAFT)
                .event(BlogStateEvent.EDIT)
                .and()

                // 已驳回的笔记修改后回到草稿。
                .withExternal()
                .source(BlogState.REJECTED).target(BlogState.DRAFT)
                .event(BlogStateEvent.EDIT)
                .and()

                // 草稿提交审核。
                .withExternal()
                .source(BlogState.DRAFT).target(BlogState.PENDING_REVIEW)
                .event(BlogStateEvent.SUBMIT)
                .and()

                // 已驳回的笔记直接重新提交。
                .withExternal()
                .source(BlogState.REJECTED).target(BlogState.PENDING_REVIEW)
                .event(BlogStateEvent.SUBMIT)
                .and()

                // 审核通过，发布并登记 Feed 推送任务。
                .withExternal()
                .source(BlogState.PENDING_REVIEW).target(BlogState.PUBLISHED)
                .event(BlogStateEvent.APPROVE)
                .and()

                // 审核驳回，保存驳回原因。
                .withExternal()
                .source(BlogState.PENDING_REVIEW).target(BlogState.REJECTED)
                .event(BlogStateEvent.REJECT);
    }

    @Bean
    public StateMachinePersister<BlogState, BlogStateEvent, Blog> blogStatePersister() {
        return new DefaultStateMachinePersister<>(new StateMachinePersist<BlogState, BlogStateEvent, Blog>() {
            @Override
            public void write(StateMachineContext<BlogState, BlogStateEvent> context, Blog blog) {
                // 数据库更新由 Action 完成，这个适配器只负责恢复状态。
            }

            @Override
            public StateMachineContext<BlogState, BlogStateEvent> read(Blog blog) {
                BlogState state = BlogState.fromCode(blog.getStatus());
                // 恢复时也保留逻辑 ID，@WithStateMachine(id=...) 才能匹配每个工厂实例。
                return new DefaultStateMachineContext<>(state, null, null, null, null, MACHINE_ID);
            }
        });
    }
}
