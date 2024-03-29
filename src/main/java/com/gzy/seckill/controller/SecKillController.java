package com.gzy.seckill.controller;

import com.gzy.seckill.exception.GlobalException;
import com.gzy.seckill.pojo.SeckillOrder;
import com.gzy.seckill.pojo.User;
import com.gzy.seckill.rabbitmq.MQSender;
import com.gzy.seckill.service.IGoodsService;
import com.gzy.seckill.service.IOrderService;
import com.gzy.seckill.service.ISeckillOrderService;
import com.gzy.seckill.utils.JsonUtil;
import com.gzy.seckill.vo.SeckillGoodsVo;
import com.gzy.seckill.vo.RespBean;
import com.gzy.seckill.vo.RespBeanEnum;
import com.gzy.seckill.vo.SeckillMessage;
import com.wf.captcha.ArithmeticCaptcha;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Controller;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Controller
@RequestMapping("/seckill")
public class SecKillController implements InitializingBean {

    @Autowired
    private IGoodsService goodsService;

    @Autowired
    private ISeckillOrderService seckillOrderService;

    @Autowired
    private IOrderService orderService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private RedisScript<Long> redisScript;

    @Autowired
    private MQSender mqSender;

    private final Map<Long, Boolean> emptyStockMap = new HashMap<>();

    @GetMapping(value = "/captcha")
    public void verifyCaptcha(User user, Long goodsId, HttpServletResponse response) {
        if (user == null || goodsId < 0) {
            throw new GlobalException(RespBeanEnum.REQUEST_ILLEGAL);
        }
        response.setContentType("image/jpg");
        response.setHeader("Pragma", "No-cache");
        response.setHeader("Cache-Control", "no-cache");
        response.setDateHeader("Expires", 0);

        ArithmeticCaptcha captcha = new ArithmeticCaptcha(130, 32, 3);
        redisTemplate.opsForValue().set("captcha:" + user.getId() + ":" + goodsId, captcha.text(), 10, TimeUnit.MINUTES);
        try {
            captcha.out(response.getOutputStream());
        } catch (IOException e) {
            log.error("验证码生成失败", e.getMessage());
        }
    }

    @GetMapping(value = "/path")
    @ResponseBody
    public RespBean getPath(User user, Long goodsId, String captcha) {
        if (user == null) {
            return RespBean.error(RespBeanEnum.SESSION_ERROR);
        }

        boolean check = orderService.checkCaptcha(user, goodsId, captcha);
        if (!check) {
            return RespBean.error(RespBeanEnum.ERROR_CAPTCHA);
        }
        String str = orderService.createPath(user, goodsId);
        return RespBean.success(str);
    }

    @RequestMapping(value = "{path}/doSeckill", method = RequestMethod.POST)
    @ResponseBody
    public RespBean doSeckill(@PathVariable String path, User user, Long goodsId) {
        if (user == null) {
            return RespBean.error(RespBeanEnum.SESSION_ERROR);
        }

        // 检查URL路径
        ValueOperations<String, Object> valueOperations = redisTemplate.opsForValue();
        boolean check = orderService.checkPath(user, goodsId, path);
        if (!check) {
            return RespBean.error(RespBeanEnum.REQUEST_ILLEGAL);
        }

        // 从Redis检查是否重复抢购
        SeckillOrder seckillOrder = (SeckillOrder) redisTemplate.opsForValue().get("order:" + user.getId() + ":" + goodsId);
        if (seckillOrder != null) {
            return RespBean.error(RespBeanEnum.REPEAT_ERROR);
        }

        // 从内存中检查库存
        if (emptyStockMap.get(goodsId)) {
            return RespBean.error(RespBeanEnum.EMPTY_STOCK);
        }

        // 从Redis获取库存
        Long stock = redisTemplate.execute(redisScript, Collections.singletonList("seckillGoods:" + goodsId), Collections.EMPTY_LIST);
        if (stock == null) {
            throw new GlobalException(RespBeanEnum.ERROR);
        }
        if (stock < 0) {
            emptyStockMap.put(goodsId, true);
            valueOperations.set("seckillGoods:" + goodsId, 0);
            return RespBean.error(RespBeanEnum.EMPTY_STOCK);
        }

        // 通过RabbitMQ发送消息, 进数据库扣库存
        SeckillMessage seckillMessage = new SeckillMessage(user, goodsId);
        mqSender.sendSeckillMessage(JsonUtil.object2JsonStr(seckillMessage));
        return RespBean.success(0);
    }

    @GetMapping("getResult")
    @ResponseBody
    public RespBean getResult(User user, Long goodsId) {
        if (user == null) {
            return RespBean.error(RespBeanEnum.SESSION_ERROR);
        }
        Long orderId = seckillOrderService.getResult(user, goodsId);
        return RespBean.success(orderId);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        List<SeckillGoodsVo> list = goodsService.findGoodsVo();
        if (CollectionUtils.isEmpty(list)) {
            return;
        }
        for (SeckillGoodsVo seckillGoodsVo : list) {
            redisTemplate.opsForValue().set("seckillGoods:" + seckillGoodsVo.getId(), seckillGoodsVo.getStockCount());
            emptyStockMap.put(seckillGoodsVo.getId(), false);
        }
    }
}
