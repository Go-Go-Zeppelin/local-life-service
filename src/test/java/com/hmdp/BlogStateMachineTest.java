package com.hmdp;

import com.hmdp.service.blog.BlogEvent;
import com.hmdp.service.blog.BlogStatus;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlogStateMachineTest {
    @Test
    void onlyDeclaredTransitionsAreAllowed() {
        assertEquals(BlogStatus.DRAFT, BlogEvent.EDIT.targetFrom(0).get());
        assertEquals(BlogStatus.DRAFT, BlogEvent.EDIT.targetFrom(3).get());
        assertEquals(BlogStatus.PENDING_REVIEW, BlogEvent.SUBMIT.targetFrom(0).get());
        assertEquals(BlogStatus.PENDING_REVIEW, BlogEvent.SUBMIT.targetFrom(3).get());
        assertEquals(BlogStatus.PUBLISHED, BlogEvent.APPROVE.targetFrom(1).get());
        assertEquals(BlogStatus.REJECTED, BlogEvent.REJECT.targetFrom(1).get());
        for (BlogEvent event : BlogEvent.values()) {
            assertFalse(event.targetFrom(2).isPresent(), "已发布笔记冻结内容和状态");
            assertFalse(event.targetFrom(null).isPresent());
            assertFalse(event.targetFrom(99).isPresent());
        }
        assertFalse(BlogEvent.EDIT.targetFrom(1).isPresent());
        assertFalse(BlogEvent.APPROVE.targetFrom(0).isPresent());
        assertFalse(BlogEvent.REJECT.targetFrom(3).isPresent());
    }
}
