package com.hmdp;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.controller.ShopController;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopPaginationTest {
    private ShopMapper shopMapper;
    private ShopServiceImpl service;

    @BeforeEach
    void setUp() {
        shopMapper = mock(ShopMapper.class);
        service = new ShopServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", shopMapper);
    }

    @Test
    void categoryPageWithoutCoordinatesSkipsCountQuery() {
        doReturn(new Page<Shop>()).when(shopMapper).selectPage(any(Page.class), any(QueryWrapper.class));

        service.queryShopByType(1, 2, null, null);

        ArgumentCaptor<Page> page = ArgumentCaptor.forClass(Page.class);
        verify(shopMapper).selectPage(page.capture(), any(QueryWrapper.class));
        assertFalse(page.getValue().searchCount());
    }

    @Test
    void namePageSkipsCountQuery() {
        IShopService shopService = mock(IShopService.class);
        QueryChainWrapper<Shop> query = mock(QueryChainWrapper.class);
        when(shopService.query()).thenReturn(query);
        when(query.like(anyBoolean(), anyString(), any())).thenReturn(query);
        when(query.page(any(Page.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ShopController controller = new ShopController();
        controller.shopService = shopService;
        controller.queryShopByName("咖啡", 1);

        ArgumentCaptor<Page> page = ArgumentCaptor.forClass(Page.class);
        verify(query).page(page.capture());
        assertFalse(page.getValue().searchCount());
    }
}
