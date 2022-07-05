package com.gzy.seckill.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gzy.seckill.pojo.Order;
import com.gzy.seckill.pojo.User;
import com.gzy.seckill.vo.GoodsVo;
import com.gzy.seckill.vo.OrderDetailVo;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author zhoubin
 * @since 2022-06-02
 */
public interface IOrderService extends IService<Order> {

    Order seckill(User user, GoodsVo goods);

    boolean checkCaptcha(User user, Long goodsId, String captcha);

    String createPath(User user, Long goodsId);

    boolean checkPath(User user, Long goodsId, String path);

    OrderDetailVo detail(Long orderId);
}
