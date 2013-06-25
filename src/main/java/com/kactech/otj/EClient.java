/*******************************************************************************
 *              OTj
 * Low-level client-side library for Open Transactions in Java
 * 
 * Copyright (C) 2013 by Piotr Kopeć (kactech)
 * 
 * EMAIL: pepe.kopec@gmail.com
 * 
 * BITCOIN: 1ESADvST7ubsFce7aEi2B6c6E2tYd4mHQp
 * 
 * OFFICIAL PROJECT PAGE: https://github.com/kactech/OTj
 * 
 * -------------------------------------------------------
 * 
 * LICENSE:
 * This program is free software: you can redistribute it
 * and/or modify it under the terms of the GNU Affero
 * General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your
 * option) any later version.
 * 
 * ADDITIONAL PERMISSION under the GNU Affero GPL version 3
 * section 7: If you modify this Program, or
 * any covered work, by linking or combining it with other
 * code, such other code is not for that reason alone subject
 * to any of the requirements of the GNU Affero GPL version 3.
 * (==> This means if you are only using the OTj, then you
 * don't have to open-source your code--only your changes to
 * OTj itself must be open source. Similar to
 * LGPLv3, except it applies to software-as-a-service, not
 * just to distributing binaries.)
 * Anyone using my library is given additional permission
 * to link their software with any BSD-licensed code.
 * 
 * -----------------------------------------------------
 * 
 * You should have received a copy of the GNU Affero General
 * Public License along with this program. If not, see:
 * http://www.gnu.org/licenses/
 * 
 * If you would like to use this software outside of the free
 * software license, please contact Piotr Kopeć.
 * 
 * DISCLAIMER:
 * This program is distributed in the hope that it will be
 * useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 * PURPOSE. See the GNU Affero General Public License for
 * more details.
 ******************************************************************************/
package com.kactech.otj;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kactech.otj.MSG.GetBoxReceiptResp;
import com.kactech.otj.OT.BoxRecord;
import com.kactech.otj.OT.TransactionReport;
import com.kactech.otj.model.BasicUserAccount;
import com.kactech.otj.model.ConnectionInfo;
import com.kactech.otj.model.UserAccount;
import com.thoughtworks.xstream.annotations.XStreamAlias;

public class EClient implements Closeable, ReqNumManager {
	static final Logger logger = LoggerFactory.getLogger(EClient.class);
	static final String userAccountFile = "userAccount.xml";
	static final String stateFile = "state.xml";

	@XStreamAlias("EClientState")
	public static class State {
		String assetType;
		String accountID;
		LinkedList<Long> transactionNums;
		LinkedList<Long> issuedNums;
	}

	Path dir;
	ConnectionInfo connInfo;
	String assetType;

	Long reqNum;
	State state;

	Client client;

	OT.Account cachedAccount;

	public EClient(Path dir, ConnectionInfo connInfo) {
		super();
		this.dir = dir;
		this.connInfo = connInfo;
	}

	public void init() {
		this.dir.toFile().mkdirs();
		// user account
		UserAccount uacc = null;
		try {
			uacc = (UserAccount) Engines.xstream.fromXML(Utils.read(dir.resolve(userAccountFile)));
			logger.info("local user account loaded");
		} catch (Exception e) {
			logger.warn(e.getMessage());
		}
		if (uacc == null) {
			logger.info("creating local user account");
			uacc = new BasicUserAccount().generate();
			try {
				Utils.writeDirs(dir.resolve(userAccountFile), Engines.xstream.toXML(uacc));
			} catch (IOException e) {
				logger.error("saving " + userAccountFile + " file", e);
				throw new RuntimeException(e);
			}
		}

		// state
		try {
			state = (State) Engines.xstream.fromXML(Utils.read(dir.resolve(stateFile)));
			logger.info("state loaded");
		} catch (Exception e) {
			logger.warn(e.getMessage());
		}
		if (state == null) {
			logger.info("creating new state");
			state = new State();
			state.assetType = assetType;
			/*
			state.transactionNums = new OT.NumList(connInfo.getID());
			state.issuedNums = new OT.NumList(connInfo.getID());
			*/
			state.transactionNums = new LinkedList<Long>();
			state.issuedNums = new LinkedList<Long>();
		}

		logger.info("creating client");
		client = new Client(uacc, connInfo.getID(), connInfo.getPublicKey(), new JeromqTransport(
				connInfo.getEndpoint()));
		client.setReqNumManager(this);

		// if accountID is null than create one
		if (state.accountID == null) {
			if (assetType == null) {
				String msg = "I want to create asset account but assetType not set";
				logger.error(msg);
				throw new IllegalStateException(msg);
			}
			logger.info("creating asset account");
			MSG.CreateAccountResp resp = client.createAccount(state.assetType);
			if (!resp.getSuccess()) {
				String msg = "cannot create asset account";
				logger.error(msg);
				throw new IllegalStateException(msg);
			}
			state.accountID = resp.getAccountID();
		}
		logger.info("init done\nnymID: {}\naccountID: {}", client.getAccount().getNymID(), state.accountID);
	}

	public Client getClient() {
		return client;
	}

	@Override
	public void close() throws IOException {
		client.close();
	}

	@Override
	public Long getReqNum(Client client) {
		if (reqNum != null)
			return reqNum++;
		try {
			reqNum = client.getRequestRaw();
		} catch (Client.NotInEnvelopeException e) {
			logger.warn("probably have no user account at that server- will register from local data");
			if (!_createUserAccount().getSuccess())
				throw new IllegalStateException("cannot create user account");
			reqNum = client.getRequestRaw();
		}
		return reqNum++;
	}

	public void saveState() {
		try {
			Utils.writeDirs(dir.resolve(stateFile), Engines.xstream.toXML(state));
			logger.info("state saved");
		} catch (IOException e) {
			logger.error("saving state", e);
			throw new RuntimeException(e);
		}
	}

	public void notarizeTransaction(String sendTo, long amount) throws Exception {
		ensureTransNums();
		processInbox();
		OT.Ledger outboxLedger = client.getOutbox(state.accountID).getOutboxLedger();
		String nymboxHash = client.getNymbox().getNymboxHash();
		OT.Account account = client.getAccount(state.accountID).getAssetAccount();
		logger.info("balance: {}", account.getBalance().getAmount());
		notarizeTransaction(sendTo, amount, account, outboxLedger, nymboxHash);
		processNymbox();
	}

	private void notarizeTransaction(String sendTo, long amount, OT.Account acc, OT.Ledger outboxLedger,
			String nymboxHash)
			throws Exception {

		List<OT.TransactionReport> reports = makeOutboxReports(outboxLedger);

		PrivateKey signingKey = client.getAccount().getCpairs().get("S").getPrivate();
		OT.Pseudonym nums = makeNums();
		Long transactionNum = state.transactionNums.peek();

		OT.Ledger ledger = from(acc);
		ledger.setType(OT.Ledger.Type.message);

		OT.Transaction tx = from(ledger);
		tx.setType(OT.Transaction.Type.transfer);
		tx.setTransactionNum(transactionNum);

		OT.Item transfer = from(tx);
		transfer.setType(OT.Item.Type.transfer);
		transfer.setStatus(OT.Item.Status.request);
		transfer.setAmount(amount);
		transfer.setToAccountID(sendTo);

		Engines.render(transfer, signingKey);
		tx.getItems().add(transfer);

		OT.Item balance = from(tx);
		balance.setType(OT.Item.Type.balanceStatement);
		balance.setStatus(OT.Item.Status.request);
		balance.setAmount(acc.getBalance().getAmount() - transfer.getAmount());
		balance.setAttachment(new OT.ArmoredString(Engines.xstream.toXML(nums)));

		TransactionReport report = from(transfer);
		reports.add(report);
		balance.setTransactionReport(reports);

		Engines.render(balance, signingKey);
		tx.getItems().add(balance);

		tx.setDateSigned(System.currentTimeMillis() / 1000);
		Engines.render(tx, signingKey);

		ledger.getTransactions().add(tx);
		Engines.render(ledger, signingKey);

		//System.out.println(json(tx));

		MSG.NotarizeTransactionsResp resp = client.notarizeTransaction(ledger, nymboxHash);

		if (resp.getSuccess()) {
			nums.transactionNums.removeNum(transactionNum);
			//nums.issuedNums.removeNum(transactionNum);
			takeNumsFrom(nums);
			//removeTransactioNum(transactionNum);

			//System.out.println(json(state));
		}
		logger.info("notarize transaction success: {}", resp.getSuccess());
	}

	public void reloadState() {
		takeNumsFrom(_createUserAccount().getNymfile().getEntity());
	}

	public OT.Account getAccount() {
		return cachedAccount = client.getAccount(state.accountID).getAssetAccount();
	}

	public void processInbox() throws Exception {
		ensureTransNums();
		MSG.GetInboxResp inbox = client.getInbox(state.accountID);
		OT.Ledger inboxLedger = inbox.getInboxLedger();
		if (inboxLedger.getInboxRecords() == null)
			return;
		OT.Ledger outboxLedger = client.getOutbox(state.accountID).getOutboxLedger();
		OT.Account account = client.getAccount(state.accountID).getAssetAccount();
		processInbox(inboxLedger, account, outboxLedger);
		processNymbox();
	}

	private void processInbox(OT.Ledger inboxLedger, OT.Account assetAcount, OT.Ledger outboxLedger) throws Exception {
		if (inboxLedger.getInboxRecords() != null) {
			List<OT.TransactionReport> reports = makeOutboxReports(outboxLedger);
			// getNymbox for nymboxHash
			String nymboxHash = client.getNymbox().getNymboxHash();
			long balanceAmount = assetAcount.getBalance().getAmount();
			PrivateKey signingKey = client.getAccount().getCpairs().get("S").getPrivate();
			OT.Pseudonym nums = makeNums();
			//System.out.println(json(nums));
			Long transactionNum = state.transactionNums.peek();

			//Long transactionNum = getNum(nym);

			// create ledger
			OT.Ledger pled = new OT.Ledger();
			pled.setType(OT.Ledger.Type.message);
			pled.setAccountID(inboxLedger.getAccountID());
			pled.setServerID(inboxLedger.getServerID());
			pled.setUserID(inboxLedger.getUserID());
			pled.setNumPartialRecords(0);
			pled.setVersion("2.0");

			OT.Transaction ptx = new OT.Transaction();
			ptx.setType(OT.Transaction.Type.processInbox);
			ptx.setAccountID(inboxLedger.getAccountID());
			ptx.setServerID(inboxLedger.getServerID());
			ptx.setUserID(inboxLedger.getUserID());
			ptx.setInReferenceTo(0l);
			ptx.setTransactionNum(transactionNum);

			// create new list
			ptx.setItems(new ArrayList<OT.Item>());

			for (OT.BoxRecord rec : inboxLedger.getInboxRecords()) {
				OT.Item item;
				switch (rec.getType()) {
				case pending:
					balanceAmount += rec.getDisplayValue();
					// create accept item
					item = from(ptx);
					item.setAmount(rec.getDisplayValue());
					item.setInReferenceTo(rec.getInReferenceTo());
					item.setStatus(OT.Item.Status.request);
					item.setType(OT.Item.Type.acceptPending);

					nums.getIssuedNums().removeNum(transactionNum);
					nums.getIssuedNums().removeNum(rec.getInRefDisplay());

					Engines.render(item, signingKey);
					ptx.getItems().add(item);
					break;
				case transferReceipt:
					item = from(ptx);
					item.setAmount(rec.getDisplayValue());
					item.setInReferenceTo(rec.getInReferenceTo());
					item.setStatus(OT.Item.Status.request);
					item.setType(OT.Item.Type.acceptItemReceipt);

					//System.out.println(json(nums));
					nums.getIssuedNums().removeNum(transactionNum);
					nums.getIssuedNums().removeNum(rec.getInRefDisplay());
					//popIssuedNum(nums);
					//popIssuedNum(nums);
					Engines.render(item, signingKey);
					ptx.getItems().add(item);
					break;
				default:
					System.err.println(json(rec));
				}
			}
			// create balanceStatement
			OT.Item balance = from(ptx);
			balance.setAmount(balanceAmount);
			balance.setInReferenceTo(0l);
			balance.setStatus(OT.Item.Status.request);
			balance.setType(OT.Item.Type.balanceStatement);
			//System.out.println(json(nums));
			balance.setAttachment(new OT.ArmoredString(Engines.xstream.toXML(nums)));
			if (reports.size() > 0)
				balance.setTransactionReport(reports);

			Engines.render(balance, signingKey);
			ptx.getItems().add(balance);

			Engines.render(ptx, signingKey);
			pled.setTransactions(Collections.singletonList(ptx));
			Engines.render(pled, signingKey);

			//System.out.println(json(pled));
			MSG.ProcessInboxResp resp = client.processInbox(pled, nymboxHash);
			// save removed nums
			if (resp.getSuccess()) {
				//takeNumsFrom(nums);
				boolean balanceRejected = false;
				if (resp.getResponseLedger().getTransactions().size() > 1)
					logger.warn("inbox response ledger contains more than 1 tx");
				OT.Transaction tx = resp.getResponseLedger().getTransactions().iterator().next();
				for (OT.Item item : tx.getItems())
					if (item.getType() == OT.Item.Type.atBalanceStatement)
						if (item.getStatus() == OT.Item.Status.rejection) {
							logger.warn("inbox balance rejected");
							balanceRejected = true;
							break;
						}
				if (balanceRejected) {
					removeTransactioNum(transactionNum);
				} else {
					nums.getTransactionNums().removeNum(transactionNum);
					nums.getIssuedNums().removeNum(transactionNum);
					takeNumsFrom(nums);
				}
				//removeTransactioNum(transactionNum);
			}
			logger.info("process inbox success: {}", resp.getSuccess());
		}
	}

	public void ensureTransNums() {
		if (state.transactionNums.size() < 30) {
			if (!client.getTransactionNum(client.getNymbox().getNymboxHash()).getSuccess()) {
				logger.error("couldn't get new transaction nums");
				throw new IllegalStateException("why?");
			}
			processNymbox();
		}
	}

	public void processNymbox() {
		MSG.GetNymboxResp getNymbox = client.getNymbox();
		for (OT.BoxRecord rec : getNymbox.getNymboxLedger().getNymboxRecords())
			if (rec.getType() == OT.Transaction.Type.message) {
				GetBoxReceiptResp receipt = client.getBoxReceipt(getNymbox.getNymID(), getNymbox.getNymboxLedger()
						.getType(), rec.getTransactionNum());
				OT.Transaction box = receipt.getBoxReceipt();
				MSG.SendUserMessage send = ((MSG.Message) box.getInReferenceToContent()).getSendUserMessage();
				byte[] data = send.getMessagePayload().getData();
				try {
					String msg = Utils.open(data, client.getAccount().getCpairs().get("E").getPrivate());
					logger.info("\nmail from {}:\n{}", send.getNymID(), msg);
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		if (getNymbox.getNymboxLedger().getNymboxRecords().size() > 1)
			processNymbox(getNymbox);
	}

	private void processNymbox(MSG.GetNymboxResp nymbox) {
		if (nymbox == null)
			nymbox = client.getNymbox();

		OT.Ledger nymled = nymbox.getNymboxLedger();
		PrivateKey signingKey = client.getAccount().getCpairs().get("S").getPrivate();
		OT.Ledger otled = new OT.Ledger();

		otled.transactions = new ArrayList<OT.Transaction>();
		otled.accountID = nymled.accountID;
		otled.userID = nymled.userID;
		otled.serverID = nymled.serverID;
		otled.type = OT.Ledger.Type.message;
		otled.version = nymled.version;//"2.0"

		OT.Transaction otx = new OT.Transaction();
		otx.items = new ArrayList<OT.Item>();
		//otx.parent = otled;//TODO
		otx.inReferenceTo = 0l;
		otx.accountID = otled.accountID;
		otx.userID = otled.userID;
		otx.serverID = otled.serverID;
		otx.transactionNum = 0l;
		otx.type = OT.Transaction.Type.processNymbox;

		if (nymled.nymboxRecords != null)
			for (OT.BoxRecord nr : nymled.nymboxRecords) {
				//System.out.println(nr.getType());
				OT.Item item;// = new OT.Item();
				switch (nr.type) {
				case message:
					item = from(otx);
					item.inReferenceTo = nr.transactionNum;
					item.type = OT.Item.Type.acceptMessage;
					item.status = OT.Item.Status.request;
					Engines.render(item, signingKey);
					otx.items.add(item);
					break;
				case replyNotice:
					item = from(otx);
					item.inReferenceTo = nr.transactionNum;
					item.type = OT.Item.Type.acceptNotice;
					item.status = OT.Item.Status.request;
					Engines.render(item, signingKey);
					otx.items.add(item);
					break;
				case blank:
					item = from(otx);
					item.inReferenceTo = nr.transactionNum;
					item.status = OT.Item.Status.request;
					item.type = OT.Item.Type.acceptTransaction;
					logger.info("we've got new tx# from server");
					state.issuedNums.addAll(nr.totalListOfNumbers);
					state.transactionNums.addAll(nr.totalListOfNumbers);
					item.totalListOfNumbers = nr.totalListOfNumbers;
					Engines.render(item, signingKey);
					otx.items.add(item);
					break;
				case successNotice:
					item = from(otx);//TODO hah, need FellowTraveler
					item.inReferenceTo = nr.transactionNum;
					item.status = OT.Item.Status.request;
					item.type = OT.Item.Type.acceptNotice;
					state.issuedNums.addAll(nr.totalListOfNumbers);
					state.transactionNums.addAll(nr.totalListOfNumbers);
					item.totalListOfNumbers = nr.totalListOfNumbers;
					Engines.render(item, signingKey);
					otx.items.add(item);
					break;
				default:
					System.err.println(json(nr));
				}
			}
		OT.Item item = from(otx);
		item.type = OT.Item.Type.transactionStatement;
		item.status = OT.Item.Status.request;
		OT.Pseudonym nums = makeNums();
		//System.out.println(json(nums));
		item.attachment = new OT.ArmoredString(Engines.xstream.toXML(nums));
		//System.err.println(item.attachment.getUnarmored());
		item.transactionNum = 0l;
		item.inReferenceTo = 0l;
		Engines.render(item, signingKey);
		//System.out.println(item.raw);
		otx.items.add(item);
		otx.dateSigned = System.currentTimeMillis() / 1000;
		Engines.render(otx, signingKey);
		otled.transactions.add(otx);
		Engines.render(otled, signingKey);

		MSG.ProcessNymboxResp resp = client.processNymbox(otled, nymbox.getNymboxHash());
		logger.info("process nymbox success: {}", resp.getSuccess());
	}

	private MSG.CreateUserAccountResp _createUserAccount() {
		UserAccount account = client.getAccount();
		Map<String, KeyPair> pairs, cpairs;
		pairs = account.getPairs();
		cpairs = account.getCpairs();

		OT.Pseudonym credentialList = new OT.Pseudonym();
		OT.CredentialMap credentials = new OT.CredentialMap();

		String nymIDSource = account.getNymIDSource();
		String nymID = account.getNymID();
		//System.out.println(nymIDSource);
		//System.out.println(nymID);
		OT.MasterCredential masterCredential = new OT.MasterCredential();
		masterCredential.setNymID(nymID);
		masterCredential.setNymIDSource(new OT.ArmoredString(nymIDSource));
		OT.PublicContents publicContents = new OT.PublicContents();
		for (Entry<String, String> e : account.getSources().entrySet())
			publicContents.getPublicInfos().put(e.getKey(), new OT.PublicInfo(e.getKey(), e.getValue()));
		publicContents.setCount(3);

		masterCredential.setPublicContents(publicContents);

		Engines.render(masterCredential, pairs.get("S").getPrivate());
		//System.out.println(masterCredential.getSigned());
		String masterCredentialID = Utils.samy62(masterCredential.getSigned().trim().getBytes(Utils.UTF8));// yes, trim... fuck!
		//String masterPublic = AsciiA.setString(masterCredential.getSigned());

		//keyCredential w/ masterPublic
		OT.KeyCredential masterSigned = new OT.KeyCredential();
		masterSigned.setNymID(nymID);
		masterSigned.setNymIDSource(new OT.ArmoredString(nymIDSource));
		masterSigned.setMasterCredentialID(masterCredentialID);
		masterSigned.setMasterPublic(masterCredential);
		publicContents = new OT.PublicContents();
		for (Entry<String, String> e : account.getCsources().entrySet())
			publicContents.getPublicInfos().put(e.getKey(), new OT.PublicInfo(e.getKey(), e.getValue()));
		publicContents.setCount(3);

		masterSigned.setPublicContents(publicContents);

		Engines.render(masterSigned, pairs.get("S").getPrivate());
		//System.out.println(keyCredentialP.getSigned());

		OT.KeyCredential keyCredential = new OT.KeyCredential();
		keyCredential.setNymID(nymID);
		keyCredential.setNymIDSource(new OT.ArmoredString(nymIDSource));
		keyCredential.setMasterCredentialID(masterCredentialID);
		keyCredential.setMasterSigned(masterSigned);

		Engines.render(keyCredential, cpairs.get("S").getPrivate());
		//System.out.println(keyCredential.getSigned());
		String keyCredentialID = Utils.samy62(keyCredential.getSigned().trim().getBytes(Utils.UTF8));// yes, trim... fuck!

		credentials = new OT.CredentialMap();
		credentials.put(masterCredentialID, masterCredential);
		credentials.put(keyCredentialID, keyCredential);

		credentialList = new OT.Pseudonym();
		credentialList.setNymID(nymID);
		credentialList.setNymIDSource(new OT.ArmoredString(nymIDSource));
		credentialList.setMasterCredential(new OT.CredentialIdentifier(masterCredentialID, null, true));
		credentialList.setKeyCredential(new OT.CredentialIdentifier(keyCredentialID, masterCredentialID, true));

		//RequestTemplates
		//nym.setVersion(new OT.Version("1.0"));
		return client.createUserAccountNew(credentialList, credentials);
	}

	private void removeTransactioNum(Long num) {
		Iterator<Long> li = state.transactionNums.iterator();
		while (li.hasNext())
			if (li.next().equals(num)) {
				li.remove();
				break;
			}
	}

	private void takeNumsFrom(OT.Pseudonym nym) {
		state.transactionNums.clear();
		if (nym.getTransactionNums() != null)
			state.transactionNums.addAll(nym.getTransactionNums());
		state.issuedNums.clear();
		if (nym.getIssuedNums() != null)
			state.issuedNums.addAll(nym.getIssuedNums());
	}

	private OT.Pseudonym makeNums() {
		OT.Pseudonym nums = new OT.Pseudonym();
		nums.setTransactionNums(new OT.NumList(client.getServerID(), state.transactionNums));
		nums.setIssuedNums(new OT.NumList(client.getServerID(), state.issuedNums));
		return nums;
	}

	static String json(Object obj) {
		return Engines.gson.toJson(obj);
	}

	static List<OT.TransactionReport> makeOutboxReports(OT.Ledger outbox) {
		List<OT.TransactionReport> reps = new ArrayList<OT.TransactionReport>();
		if (outbox.getOutboxRecords() != null)
			for (BoxRecord rec : outbox.getOutboxRecords()) {
				OT.TransactionReport rep = new OT.TransactionReport();
				rep.setServerID(outbox.getServerID());
				rep.setAccountID(outbox.getAccountID());
				rep.setUserID(outbox.getUserID());
				rep.setAdjustment(rec.getDisplayValue());
				rep.setClosingTransactionNum(0l);
				rep.setInReferenceTo(rec.getInReferenceTo());
				rep.setTransactionNum(rec.getTransactionNum());
				rep.setType(OT.Item.Type.transfer);
				reps.add(rep);
			}
		return reps;
	}

	static OT.Item from(OT.Transaction tx) {
		OT.Item item = new OT.Item();
		item.toAccountID = "";
		item.amount = 0l;
		item.fromAccountID = tx.accountID;
		item.userID = tx.userID;
		item.serverID = tx.serverID;
		item.transactionNum = tx.transactionNum;
		item.setInReferenceTo(0l);
		return item;
	}

	static OT.Ledger from(OT.Account account) {
		OT.Ledger pled = new OT.Ledger();
		pled.setAccountID(account.getAccountID());
		pled.setServerID(account.getServerID());
		pled.setUserID(account.getUserID());
		pled.setNumPartialRecords(0);
		pled.setVersion("2.0");

		pled.setTransactions(new ArrayList<OT.Transaction>());

		return pled;
	}

	static OT.Transaction from(OT.Ledger ledger) {
		OT.Transaction ptx = new OT.Transaction();
		ptx.setAccountID(ledger.getAccountID());
		ptx.setServerID(ledger.getServerID());
		ptx.setUserID(ledger.getUserID());

		ptx.setInReferenceTo(0l);

		ptx.setItems(new ArrayList<OT.Item>());

		return ptx;
	}

	static OT.TransactionReport from(OT.Item item) {
		OT.TransactionReport rep = new OT.TransactionReport();
		rep.setAccountID(item.getFromAccountID());
		rep.setServerID(item.getServerID());
		rep.setUserID(item.getUserID());

		rep.setAdjustment(-item.getAmount());
		rep.setInReferenceTo(item.getTransactionNum());
		rep.setClosingTransactionNum(0l);
		rep.setTransactionNum(1l);//TODO ask FT
		rep.setType(OT.Item.Type.transfer);

		return rep;
	}

	static OT.Pseudonym numsFrom(OT.Pseudonym nym) {
		OT.Pseudonym nums = new OT.Pseudonym();
		nums.setTransactionNums(new OT.NumList(nym.getTransactionNums()));
		nums.setIssuedNums(new OT.NumList(nym.getIssuedNums()));
		return nums;
	}

	public void setAssetType(String assetType) {
		this.assetType = assetType;
	}

	public String getAssetType() {
		return assetType;
	}

	public OT.Account getCachedAccount() {
		return cachedAccount;
	}
}