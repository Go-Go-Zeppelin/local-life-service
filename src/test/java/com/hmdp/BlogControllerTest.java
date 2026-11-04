package com.hmdp;

import com.hmdp.config.WebExceptionAdvice;
import com.hmdp.controller.BlogController;
import com.hmdp.dto.Result;
import com.hmdp.dto.blog.*;
import com.hmdp.service.IBlogService;
import com.hmdp.service.blog.BlogPublishFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BlogControllerTest {
    private final BlogPublishFacade facade = mock(BlogPublishFacade.class);
    private final IBlogService queries = mock(IBlogService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        BlogController controller = new BlogController();
        ReflectionTestUtils.setField(controller, "publishFacade", facade);
        ReflectionTestUtils.setField(controller, "blogService", queries);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new WebExceptionAdvice()).build();
    }

    @Test
    void createAcceptsOnlyDraftContent() throws Exception {
        BlogOwnerDTO draft = new BlogOwnerDTO();
        draft.setStatus(0);
        draft.setVersion(0L);
        when(facade.saveDraft(any())).thenReturn(Result.ok(draft));
        mvc.perform(post("/blog").contentType(MediaType.APPLICATION_JSON)
                .content("{\"shopId\":1,\"title\":\"草稿\",\"content\":\"内容\",\"status\":2,\"reviewerId\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value(0));
        ArgumentCaptor<BlogDraftCommand> command = ArgumentCaptor.forClass(BlogDraftCommand.class);
        verify(facade).saveDraft(command.capture());
        assertEquals("草稿", command.getValue().getTitle());
    }

    @Test
    void conflictAndPermissionFailuresRetainHttpStatus() throws Exception {
        when(facade.pendingReviews(1)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "没有笔记审核权限"));
        mvc.perform(get("/blog/reviews")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
        when(facade.submit(10L, 0L)).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "状态已变更"));
        mvc.perform(post("/blog/10/submit").contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void publicResponseHasNoReviewMetadataAndOwnerCannotSeeReviewerIdentity() throws Exception {
        when(queries.queryBlogById(10L)).thenReturn(Result.ok(new BlogPublicDTO()));
        mvc.perform(get("/blog/10")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").doesNotExist())
                .andExpect(jsonPath("$.data.status").doesNotExist())
                .andExpect(jsonPath("$.data.reviewerId").doesNotExist())
                .andExpect(jsonPath("$.data.rejectReason").doesNotExist());
        BlogOwnerDTO owner = new BlogOwnerDTO();
        owner.setRejectReason("请补充内容");
        when(facade.myBlog(10L)).thenReturn(Result.ok(owner));
        mvc.perform(get("/blog/of/me/10")).andExpect(jsonPath("$.data.rejectReason").value("请补充内容"))
                .andExpect(jsonPath("$.data.reviewerId").doesNotExist());
    }
}
