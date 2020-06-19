package com.iquantex.account.service;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.util.concurrent.Uninterruptibles;
import com.iquantex.account.model.AccountStore;
import com.iquantex.account.repository.AccountStoreRepository;
import com.iquantex.account.utils.JsonUtils;
import com.iquantex.phoenix.client.PhoenixClient;
import com.iquantex.phoenix.client.RpcResult;
import com.iquantex.phoenix.client.utils.RateLimiter;
import com.iquantex.samples.account.coreapi.command.AccountAllocateCmd;
import com.iquantex.samples.account.coreapi.command.AccountQueryCmd;
import com.iquantex.samples.account.coreapi.event.AccountQueryEvent;
import com.iquantex.samples.account.coreapi.other.UpperAccountTransEvent;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @author baozi
 * @date 2020/5/27 12:09 PM
 */
@JsonSerialize
@Slf4j
@Service
public class BankAccountServiceImpl implements BankAccountService {

	/** kafka发送端 */
	@Autowired
	private KafkaTemplate<String, String> kafkaTemplate;

	/** phoenix 客户端 */
	@Autowired
	private PhoenixClient phoenixClient;

	/** 账户存储 */
	@Autowired
	private AccountStoreRepository accountStoreRepository;

	@Override
	public String queryAllAccount(boolean linearizability) {

		// 1. 通过数据库查询账户列表
		List<AccountStore> accountStores = new ArrayList<>();
		accountStoreRepository.findAll().forEach(accountStore -> {
			accountStores.add(accountStore);
		});

		// 2. 查询账户信息,如果linearizability == true,会使用QueryCmd直接读取内存来
		// 达到线性一致性读. 否则会读业务数据库实现最终一致性读(业务数据库是异步写入的)
		List<AccountQueryEvent> accountQueryEvents = new ArrayList<>();
		if (linearizability) {
			accountStores.forEach(accountStore -> {
				Future<RpcResult> future = phoenixClient.send(new AccountQueryCmd(accountStore.getAccountCode()), null);
				RpcResult<AccountQueryEvent> rpcResult = null;
				try {
					rpcResult = future.get(100, TimeUnit.SECONDS);
				}
				catch (Exception e) {
					log.error(e.getLocalizedMessage(), e);
					return;
				}
				AccountQueryEvent accountQueryEvent = rpcResult.getData();
				accountQueryEvents.add(accountQueryEvent);
			});
		}
		else {
			accountStores
					.forEach(accountStore -> accountQueryEvents.add(new AccountQueryEvent(accountStore.getAccountCode(),
							accountStore.getBalanceAmt(), accountStore.getSuccessTransferOut(),
							accountStore.getFailTransferOut(), accountStore.getSuccessTransferIn())));
		}

		// 3. 转换为html
		double totalBalanceAmt = 0;
		int totalSuccessTransferOut = 0;
		int totalFailTransferOut = 0;
		int totalSuccessTransferIn = 0;
		StringBuffer sb = new StringBuffer();
		sb.append("<table border=1 width='1200px'>");
		sb.append("<tr><th>银行账户</th><th>账户余额</th><th>成功转出次数</th><th>失败转出次数</th><th>成功转入次数</th></tr>");
		for (AccountQueryEvent accountQueryEvent : accountQueryEvents) {
			sb.append("<tr>");
			sb.append("<td>" + accountQueryEvent.getAccount() + "</td>");
			sb.append("<td>" + accountQueryEvent.getBalanceAmt() + "</td>");
			sb.append("<td>" + accountQueryEvent.getSuccessTransferOut() + "</td>");
			sb.append("<td>" + accountQueryEvent.getFailTransferOut() + "</td>");
			sb.append("<td>" + accountQueryEvent.getSuccessTransferIn() + "</td>");
			sb.append("</tr>");
			totalBalanceAmt += accountQueryEvent.getBalanceAmt();
			totalSuccessTransferOut += accountQueryEvent.getSuccessTransferOut();
			totalFailTransferOut += accountQueryEvent.getFailTransferOut();
			totalSuccessTransferIn += accountQueryEvent.getSuccessTransferIn();
		}
		if (accountQueryEvents.size() > 1) {
			sb.append("<tr>");
			sb.append("<td>账户数量:" + accountQueryEvents.size() + "</td>");
			sb.append("<td>账户余额汇总:" + totalBalanceAmt + "</td>");
			sb.append("<td>成功转出汇总：" + totalSuccessTransferOut + "</td>");
			sb.append("<td>失败转出汇总：" + totalFailTransferOut + "</td>");
			sb.append("<td>成功转入汇总：" + totalSuccessTransferIn + "</td>");
			sb.append("</tr>");
		}
		sb.append("</table>");
		return sb.toString();
	}

	@Override
	public boolean transAccount(UpperAccountTransEvent accountTransEvent) {
		String jsonStr = JsonUtils.encode(accountTransEvent);
		log.info("send message: {}", jsonStr);
		kafkaTemplate.send("upper-topic", accountTransEvent.getClass().getName(), jsonStr);
		return true;
	}

	@Override
	public String batchAllocate(int total, int tps, int aggregateNum) {
		log.info("start a message test: aggregateNum<{}> total<{}>, tps<{}>", aggregateNum, total, tps);
		int timeout = total / tps + 5;
		// 设置速率限制器发送请求
		RateLimiter messageTestTask = new RateLimiter(tps, total, timeout, () -> {
			String account = String.format("A%08d", new Random().nextInt(aggregateNum));
			int amt = (100 - new Random().nextInt(200));
			AccountAllocateCmd cmd = new AccountAllocateCmd(account, amt);
			phoenixClient.send(cmd, "");
		}, "account", new RateLimiter.RunMonitor(false));
		// 开始随机划拨
		messageTestTask.start();
		return String.format("开始随机转账:total=<%d>,tps=<%d>,aggregateNum=<%d>", total, tps, aggregateNum);
	}

	@Override
	public String allocate(String account, double amt) {
		AccountAllocateCmd cmd = new AccountAllocateCmd(account, amt);
		Future<RpcResult> future = phoenixClient.send(cmd, "");
		try {
			RpcResult result = future.get(10, TimeUnit.SECONDS);
			return result.getMessage();
		}
		catch (InterruptedException | ExecutionException | TimeoutException e) {
			return "rpc error: " + e.getMessage();
		}
	}

}