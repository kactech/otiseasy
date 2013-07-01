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

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.SignatureException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAPublicKeySpec;
import java.text.ParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.codec.binary.Base64;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.params.RSAKeyParameters;
import org.bouncycastle.crypto.util.PublicKeyFactory;
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyConverter;
import org.bouncycastle.openssl.PEMException;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.PEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.util.io.pem.PemReader;
import org.bouncycastle.x509.X509StreamParser;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import OTDB.Generics;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.kactech.otj.model.BasicOTSignature;
import com.kactech.otj.model.BasicSigned;
import com.kactech.otj.model.Signable;
import com.kactech.otj.model.Signed;
import com.kactech.otj.model.SigningSupport;

public class Utils {
	private static final Utils _it = new Utils();
	public static final Charset US_ASCII = Charset.forName("US-ASCII");
	public static final Charset UTF8 = Charset.forName("UTF-8");

	/*
	 * ascii armor handling
	 */
	public static String unarmor(String str, boolean escaped, String bookend) {
		String end_line = "-----END";
		boolean contentMode = false;
		boolean haveEnteredContentMode = false;
		StringBuilder builder = new StringBuilder();
		for (String line : str.split("\n")) {
			if (line.length() < 2)
				continue;
			else if (line.startsWith(escaped ? "- --" : "----")) {
				if (!haveEnteredContentMode) {
					if (line.contains(bookend)) {
						haveEnteredContentMode = true;
						contentMode = true;
					}
				} else if (contentMode && line.contains(end_line)) {
					contentMode = false;
				}
			} else if (contentMode)
				if (line.startsWith("Version:") || line.startsWith("Comment:"))
					;
				else
					builder.append(line).append('\n');
		}
		if (!haveEnteredContentMode)
			throw new IllegalStateException("EOF before ascii-armored content found, in:\n\n" + str);
		if (contentMode)
			throw new IllegalStateException("EOF while still reading content, in:\n\n" + str);
		return builder.toString();
	}

	public static String unarmor(String str, boolean escaped) {
		return unarmor(str, escaped, "-----BEGIN");
	}

	public static String unAsciiArmor(String str, boolean escaped) throws IOException, DataFormatException,
			PackerException {
		str = unarmor(str, false);
		byte[] bytes = base64Decode(str);
		bytes = zlibDecompress(bytes);
		str = unpack(bytes, String.class);
		return str;
	}

	public static byte[] zlibCompress(byte[] data) {
		Deflater deflater = new Deflater();
		deflater.setInput(data);

		ByteArrayOutputStream outputStream = new ByteArrayOutputStream(data.length);

		deflater.finish();
		byte[] buffer = new byte[1024];
		while (!deflater.finished()) {
			int count = deflater.deflate(buffer);
			outputStream.write(buffer, 0, count);
		}
		try {
			outputStream.close();
		} catch (IOException e) {
			// don't be silly
			throw new RuntimeException(e);
		}
		return outputStream.toByteArray();
	}

	public static byte[] zlibDecompress(byte[] data) throws IOException, DataFormatException {
		Inflater inflater = new Inflater(false);

		inflater.setInput(data);

		ByteArrayOutputStream outputStream = new ByteArrayOutputStream(data.length);
		byte[] buffer = new byte[1024];
		while (!inflater.finished()) {
			int count = inflater.inflate(buffer);
			if (count == 0)
				throw new DataFormatException("probably bad, has infinite loop at encoded message");
			outputStream.write(buffer, 0, count);
		}
		outputStream.close();
		byte[] output = outputStream.toByteArray();

		return output;
	}

	/*
	 * packing
	 */
	@SuppressWarnings("serial")
	public static class PackerException extends Exception {
		public PackerException(Throwable cause) {
			super(cause);
		}
	}

	@SuppressWarnings("unchecked")
	public static <T> T unpack(byte[] packed, Class<T> clazz) throws PackerException {
		try {
			if (clazz == String.class)
				return (T) Generics.String_InternalPB.parseFrom(packed).getValue();
			else if (clazz == byte[].class)
				return (T) Generics.Blob_InternalPB.parseFrom(packed).getValue().toByteArray();
			else if (clazz == Map.class) {
				packed = Generics.Blob_InternalPB.parseFrom(packed).getValue().toByteArray();
				Generics.StringMap_InternalPB gmap = Generics.StringMap_InternalPB.parseFrom(packed);
				Map<String, String> map = new HashMap<String, String>();
				for (Generics.KeyValue_InternalPB kv : gmap.getNodeList())
					map.put(kv.getKey(), kv.getValue());
				return (T) map;
			} else
				throw new IllegalArgumentException("cannot unpack " + clazz);
		} catch (InvalidProtocolBufferException e) {
			throw new PackerException(e);
		}
	}

	public static byte[] pack(ByteBuffer buffer) {
		return Generics.Blob_InternalPB.newBuilder().setValue(ByteString.copyFrom(buffer)).build().toByteArray();
	}

	public static byte[] pack(String string) {
		return Generics.String_InternalPB.newBuilder().setValue(string).build().toByteArray();
	}

	public static byte[] pack(Map<String, String> map) throws InvalidProtocolBufferException {
		Generics.StringMap_InternalPB.Builder builder = Generics.StringMap_InternalPB.newBuilder();
		for (Entry<String, String> e : map.entrySet())
			builder.addNode(Generics.KeyValue_InternalPB.newBuilder().setKey(e.getKey()).setValue(e.getValue()).build());
		return Generics.Blob_InternalPB.newBuilder().setValue(builder.build().toByteString()).build().toByteArray();
	}

	/*
	 * PEM
	 */
	public static RSAPublicKey pemReadRSAPublicKey(String str) throws IOException, InvalidKeySpecException,
			NoSuchAlgorithmException {

		byte[] keyBytes;

		PemReader reader = new PemReader(new StringReader(str));
		keyBytes = reader.readPemObject().getContent();
		reader.close();
		/*
		str = unarmor(str, false);
		keyBytes = base64Decode(str);
		*/
		AsymmetricKeyParameter keyParam = PublicKeyFactory.createKey(keyBytes);
		SubjectPublicKeyInfo publicKeyInfo = SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(keyParam);
		RSAKeyParameters rsaPubParams = (RSAKeyParameters) PublicKeyFactory.createKey(publicKeyInfo);
		RSAPublicKeySpec rsaSpec = new RSAPublicKeySpec(rsaPubParams.getModulus(), rsaPubParams.getExponent());
		RSAPublicKey pk = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(rsaSpec);
		return pk;
	}

	/*
	 * PGP public key
	 */
	public static PublicKey pgpReadPublicKey(String str) throws IOException {
		PGPObjectFactory factory = new PGPObjectFactory(
				PGPUtil.getDecoderStream(new ByteArrayInputStream(str.getBytes(US_ASCII))));
		Object o = factory.nextObject();
		if (o instanceof PGPPublicKeyRing) {
			PGPPublicKeyRing kr = (PGPPublicKeyRing) o;
			try {
				return new JcaPGPKeyConverter().getPublicKey(kr.getPublicKey());
			} catch (PGPException e) {
				throw new RuntimeException(e);
			}
		}
		return null;
	}

	/*
	 * X509
	 */
	public static X509Certificate readX509Certificate(byte[] certContent) {
		X509StreamParser parser;
		try {
			parser = X509StreamParser.getInstance("Certificate", "BC");
			parser.init(certContent);
			X509Certificate cert = (X509Certificate) parser.read();
			return cert;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

	}

	/*
	 * samy hash
	 */
	public static byte[] samyHash(byte[] input) {
		MessageDigest sha, whi;
		try {
			sha = MessageDigest.getInstance("SHA-256");
			whi = MessageDigest.getInstance("WHIRLPOOL");
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
		sha.update(input);
		whi.update(input);
		byte[] digA = sha.digest();
		byte[] digB = whi.digest();
		int l = Math.min(digA.length, digB.length);
		byte[] out = new byte[l];
		for (int i = 0; i < out.length; i++)
			out[i] = (byte) (digA[i] ^ digB[i]);
		if (true) {
			return out;
		} else {
			BigInteger biA = new BigInteger(Arrays.copyOf(digA, l));
			BigInteger biB = new BigInteger(Arrays.copyOf(digB, l));
			return biA.xor(biB).toByteArray();
		}
	}

	public static String samy62(File file) throws IOException {
		return samy62(readBytes(file));
	}

	public static String samy62(byte[] input) {
		byte[] by = samyHash(input);
		return base62Encode(new BigInteger(1, by));
	}

	/*
	 * Base64 encoding
	 * just forward to org.apache.commons.codec.binary.Base64
	 */
	public static byte[] base64Decode(String input) {
		return Base64.decodeBase64(input);
	}

	public static byte[] base64Decode(byte[] input) {
		return Base64.decodeBase64(input);
	}

	public static String base64DecodeString(String str) {
		return new String(base64Decode(str), UTF8);
	}

	public static byte[] base64Encode(byte[] input, boolean lineBreaks) {
		Base64 b = lineBreaks ? new Base64(65, new byte[] { (byte) '\n' }) : new Base64();
		return b.encode(input);
	}

	public static String base64EncodeString(byte[] input, boolean lineBreaks) {
		return new String(base64Encode(input, lineBreaks), US_ASCII);
	}

	/*
	 * Base-62 encoding
	 * based on https://github.com/opencoinage/opencoinage/blob/3a4db8d36b68ffaeb6700935fdfeb838a0e7466a/src/java/org/opencoinage/util/Base62.java
	 */

	private static final BigInteger B62_BASE = BigInteger.valueOf(62);
	public static final String B62_DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

	public static String base62Encode(BigInteger number) {
		if (number.compareTo(BigInteger.ZERO) == -1) { // number < 0
			throw new IllegalArgumentException("number must not be negative");
		}
		StringBuilder result = new StringBuilder();
		while (number.compareTo(BigInteger.ZERO) == 1) { // number > 0
			BigInteger[] divmod = number.divideAndRemainder(B62_BASE);
			number = divmod[0];
			int digit = divmod[1].intValue();
			result.insert(0, B62_DIGITS.charAt(digit));
		}
		return (result.length() == 0) ? B62_DIGITS.substring(0, 1) : result.toString();
	}

	public static BigInteger base62Decode(final String string) {
		if (string.length() == 0) {
			throw new IllegalArgumentException("string must not be empty");
		}
		BigInteger result = BigInteger.ZERO;
		int digits = string.length();
		for (int index = 0; index < digits; index++) {
			int digit = B62_DIGITS.indexOf(string.charAt(digits - index - 1));
			result = result.add(BigInteger.valueOf(digit).multiply(B62_BASE.pow(index)));
		}
		return result;
	}

	/*
	 * xml
	 */
	static DocumentBuilder _documentBuilder;

	public static DocumentBuilder getDocumentBuilder() {
		synchronized (_it) {
			if (_documentBuilder == null)
				try {
					_documentBuilder = DocumentBuilderFactory.newInstance()
							.newDocumentBuilder();
				} catch (ParserConfigurationException e) {
					throw new RuntimeException(e);
				}
			return _documentBuilder;
		}
	}

	public static Document parseXmlDocument(String str) throws SAXException, IOException {
		return getDocumentBuilder().parse(new InputSource(new StringReader(str)));
	}

	/*
	 * seal
	 */
	public static byte[] seal(String msg, String nymID, PublicKey nymKey)
			throws InvalidKeyException, InvalidAlgorithmParameterException, IllegalBlockSizeException,
			BadPaddingException {
		SecureRandom random = new SecureRandom();
		byte[] aesKey = new byte[16];
		random.nextBytes(aesKey);
		byte[] vector = new byte[16];
		random.nextBytes(vector);
		return seal(msg, nymID, nymKey, new SecretKeySpec(aesKey, "AES")
				, new IvParameterSpec(vector));
	}

	public static byte[] seal(String msg, String nymID, PublicKey nymKey, SecretKeySpec aesSecret,
			IvParameterSpec vector)
			throws InvalidKeyException, InvalidAlgorithmParameterException, IllegalBlockSizeException,
			BadPaddingException {
		ByteBuffer buff = ByteBuffer.allocate(msg.length() + 500);//donno?
		buff.order(ByteOrder.BIG_ENDIAN);
		buff.putShort((short) 1);//asymmetric
		buff.putInt(1);//array size
		buff.putInt(nymID.length() + 1);
		buff.put((nymID + '\0').getBytes(US_ASCII));

		// create encoded key and message
		Cipher cipher;
		try {
			cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		cipher.init(Cipher.ENCRYPT_MODE, aesSecret, vector);
		byte[] encrypted = cipher.doFinal((msg + '\0').getBytes(UTF8));
		try {
			cipher = Cipher.getInstance("RSA");
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		cipher.init(Cipher.WRAP_MODE, nymKey);
		byte[] encKeyBytes = cipher.wrap(aesSecret);

		buff.putInt(encKeyBytes.length);
		buff.put(encKeyBytes);
		buff.putInt(vector.getIV().length);
		buff.put(vector.getIV());
		buff.put(encrypted);
		buff.flip();

		return base64Encode(pack(buff), true);
	}

	public static String open(byte[] encryptedEnvelope, PrivateKey privateKey) throws InvalidKeyException,
			NoSuchAlgorithmException, InvalidAlgorithmParameterException, IllegalBlockSizeException,
			BadPaddingException {
		String str;
		byte[] by;
		ByteBuffer buff = ByteBuffer.wrap(encryptedEnvelope);
		buff.order(ByteOrder.BIG_ENDIAN);
		int envType = buff.getShort();// expected 1(asymmetric)
		if (envType != 1)
			throw new UnsupportedOperationException("unexpected envelope type " + envType);
		int arraySize = buff.getInt();// can result in negative integer but not expecting it here
		if (arraySize != 1)//TODO
			throw new UnsupportedOperationException("current code doesn't support multi-nym response");
		byte[] encKeyBytes = null;
		byte[] vectorBytes = null;
		for (int i = 0; i < arraySize; i++) {
			int nymIDLen = buff.getInt();
			by = new byte[nymIDLen];
			buff.get(by);
			String nymID = new String(by, 0, by.length - 1, Utils.US_ASCII);// take nymID W/O trailing \0
			//TODO nymID matching!
			int keyLength = buff.getInt();
			encKeyBytes = new byte[keyLength];
			buff.get(encKeyBytes);
			int vectorLength = buff.getInt();
			vectorBytes = new byte[vectorLength];
			buff.get(vectorBytes);

		}
		byte[] encryptedMsg = new byte[buff.remaining()];
		buff.get(encryptedMsg);

		Cipher cipher;
		try {
			cipher = Cipher.getInstance("RSA");
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		cipher.init(Cipher.UNWRAP_MODE, privateKey);
		SecretKeySpec aesKey = (SecretKeySpec) cipher.unwrap(encKeyBytes, "AES", Cipher.SECRET_KEY);
		try {
			cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		cipher.init(Cipher.DECRYPT_MODE, aesKey, new IvParameterSpec(vectorBytes));
		by = cipher.doFinal(encryptedMsg);
		str = new String(by, 0, by.length - 1, Utils.UTF8);// w/o trailing \0
		return str;
	}

	/*
	 * got it, dizzy code, it's real nymIDSource(when escaped) and publicInfo (if not escaped)
	 */
	public static String toRawPublicInfo(PublicKey publicKey, boolean escaped) {
		StringWriter sw = new StringWriter();
		PEMWriter pemw = new PEMWriter(sw);
		try {
			pemw.writeObject(publicKey);
			pemw.flush();
			pemw.close();
		} catch (IOException e) {
			// with StringBuffer? I don't think so, but throw that
			throw new RuntimeException(e);
		}
		String str = sw.getBuffer().toString();
		byte[] out = pack(ByteBuffer.wrap(str.getBytes(US_ASCII)));
		str = base64EncodeString(out, true);
		return (escaped ? "- " : "") + "-----BEGIN PUBLIC KEY-----\n" + str + (escaped ? "- " : "")
				+ "-----END PUBLIC KEY-----\n";
	}

	private static final JcaPEMKeyConverter _jcaPEMKeyConverter = new JcaPEMKeyConverter();

	public static PublicKey fromRawPublicInfo(String str, boolean escaped) {
		str = unarmor(str, escaped);
		byte[] by = base64Decode(str);
		try {
			str = unpack(by, String.class);
		} catch (PackerException e) {
			// you've fucked up something
			throw new RuntimeException(e);
		}
		PEMParser p = new PEMParser(new StringReader(str));
		Object pobj;
		try {
			pobj = p.readObject();
		} catch (IOException e) {
			// io exception? hehe
			throw new RuntimeException(e);
		}
		SubjectPublicKeyInfo spub = SubjectPublicKeyInfo.getInstance(pobj);
		try {
			return _jcaPEMKeyConverter.getPublicKey(spub);
		} catch (PEMException e) {
			// dont' bother
			throw new RuntimeException(e);
		}
	}

	public static String toNymID(PublicKey publicKey) {
		return samy62(toRawPublicInfo(publicKey, true).getBytes(US_ASCII));
	}

	public static String toNymIDSource(PublicKey publicKey) {
		String str = toRawPublicInfo(publicKey, true);
		byte[] by = pack(str);
		by = zlibCompress(by);
		return base64EncodeString(by, true);
	}

	public static PublicKey fromIDSource(String str) throws IOException, DataFormatException, PackerException,
			InvalidKeySpecException, NoSuchAlgorithmException {
		byte[] bytes = base64Decode(str);
		bytes = zlibDecompress(bytes);
		str = unpack(bytes, String.class);
		str = unarmor(str, true);
		bytes = base64Decode(str);
		str = unpack(bytes, String.class);
		return pemReadRSAPublicKey(str);
	}

	public static String toPublicInfo(PublicKey publicKey) {
		String str = toRawPublicInfo(publicKey, false);
		byte[] by = pack(str);
		by = zlibCompress(by);
		return base64EncodeString(by, true);
	}

	public static PublicKey fromPublicInfo(String str) throws IOException, DataFormatException, PackerException,
			InvalidKeySpecException, NoSuchAlgorithmException {
		byte[] bytes = base64Decode(str);
		bytes = zlibDecompress(bytes);
		str = unpack(bytes, String.class);
		str = unarmor(str, false);
		bytes = base64Decode(str);
		str = unpack(bytes, String.class);
		return pemReadRSAPublicKey(str);
	}

	public static String sign(String unsigned, PrivateKey privateKey) throws InvalidKeyException, SignatureException {
		byte[] samyHash = Utils.samyHash(unsigned.getBytes(Utils.UTF8));
		OTPssSignature signature = new OTPssSignature();
		signature.initSign(privateKey);
		signature.update(samyHash);
		byte[] sign = signature.sign();
		sign = pack(ByteBuffer.wrap(sign));

		StringBuilder signed = new StringBuilder();
		signed.append("-----BEGIN SIGNED MESSAGE-----\n")
				.append("Hash: SAMY\n\n")
				.append(unsigned)
				.append("-----BEGIN MESSAGE SIGNATURE-----\n")
				.append("Version: kactech 0.1\n\n")
				.append(Utils.base64EncodeString(sign, true))
				.append("-----END MESSAGE SIGNATURE-----\n");

		return signed.toString();
	}

	public static Signable sign(Signable signable, PrivateKey privateKey) throws InvalidKeyException,
			SignatureException {
		byte[] samyHash = Utils.samyHash(signable.getUnsigned().getBytes(Utils.UTF8));
		OTPssSignature signature = new OTPssSignature();
		signature.initSign(privateKey);
		signature.update(samyHash);
		byte[] sign = signature.sign();
		sign = pack(ByteBuffer.wrap(sign));
		String signString = Utils.base64EncodeString(sign, true);
		BasicOTSignature otSig = new BasicOTSignature();
		otSig.setValue(signString);
		otSig.setVersion("kactech 0.2");
		signable.addSignature(otSig);

		StringBuilder signed = new StringBuilder();
		signed.append("-----BEGIN SIGNED MESSAGE-----\n")
				.append("Hash: SAMY\n\n")
				.append(signable.getUnsigned())
				.append("-----BEGIN MESSAGE SIGNATURE-----\n")
				.append("Version: ").append(otSig.getVersion() + "\n\n")
				.append(otSig.getValue())
				.append("-----END MESSAGE SIGNATURE-----\n");
		signable.setSigned(signed.toString());
		return signable;
	}

	public static SigningSupport sign(SigningSupport signable, PrivateKey privateKey) throws InvalidKeyException,
			SignatureException {
		byte[] samyHash = Utils.samyHash(signable.getUnsigned().getBytes(Utils.UTF8));
		OTPssSignature signature = new OTPssSignature();
		signature.initSign(privateKey);
		signature.update(samyHash);
		byte[] sign = signature.sign();
		sign = pack(ByteBuffer.wrap(sign));
		String signString = Utils.base64EncodeString(sign, true);
		BasicOTSignature otSig = new BasicOTSignature();
		otSig.setValue(signString);
		otSig.setVersion("kactech 0.2");
		signable.addSignature(otSig, "SAMY");
		return signable;
	}

	public static boolean verify(String unsigned, PublicKey publicKey, String signature) throws PackerException,
			InvalidKeyException, SignatureException {
		OTPssSignature sign = new OTPssSignature();
		sign.initVerify(publicKey);
		byte[] by = Utils.samyHash(unsigned.getBytes(Utils.UTF8));
		sign.update(by);
		by = Utils.base64Decode(signature);
		by = Utils.unpack(by, byte[].class);
		return sign.verify(by);
	}

	public static Signed parseSigned(String content) throws ParseException {
		return parseSigned(null, content);
	}

	public static Signed parseSigned(BasicSigned signed, String content) throws ParseException {
		if (signed == null)
			signed = new BasicSigned();
		signed.setRaw(content);
		BufferedReader r = new BufferedReader(new StringReader(content));

		String line;
		BasicOTSignature sign = null;
		StringBuilder bSign = new StringBuilder();
		StringBuilder bContent = new StringBuilder();
		boolean mSign = false;//signature mode
		boolean mContent = false;
		boolean mEnteredContent = false;
		int lineN = 0;

		try {
			while ((line = r.readLine()) != null) {
				lineN++;
				if (line.length() < 2) {
					if (mSign)
						continue;
				} else if (line.charAt(0) == '-') {
					if (mSign) {
						mSign = false;
						sign.setValue(bSign.toString().trim());
						continue;
					}
					if (!mEnteredContent) {
						if (line.length() > 3 && line.startsWith("----") && line.contains("BEGIN")) {
							mEnteredContent = true;
							mContent = true;
							continue;
						} else
							continue;
					} else if (line.length() > 3 && line.startsWith("----") && line.contains("SIGNATURE")) {
						mSign = true;
						mContent = false;
						bSign = new StringBuilder();
						signed.getSignatures().add(sign = new BasicOTSignature());
						continue;
					} else if (line.length() < 3 || line.charAt(1) != ' ' || line.charAt(2) != '-')
						throw new ParseException(line, lineN);
					else
						;
				} else {
					if (mEnteredContent) {
						if (mSign) {
							if (line.length() < 2)
								continue;
							else if (line.startsWith("Version:")) {
								sign.setVersion(line.substring("Version:".length()).trim());
								continue;
							}
							else if (line.startsWith("Comment:")) {
								sign.setComment(line.substring("Comment:".length()).trim());
								continue;
							} else if (line.startsWith("Meta:")) {
								if (line.length() != 13)
									throw new ParseException("incorrect meta length", lineN);
								sign.setMeta(line.substring(9, 13));
								continue;
							}
						}
						if (mContent) {
							if (line.startsWith("Hash:")) {
								signed.setHashType(line.substring("Hash: ".length()).trim().toUpperCase());
								r.readLine();
								continue;
							}
						}
					}

				}
				if (mSign)
					bSign.append(line);
				else if (mContent)
					bContent.append(line).append('\n');
			}

			r.close();
		} catch (IOException e) {
			//not normal with reader
			throw new RuntimeException(e);
		}

		signed.setUnsigned(bContent.toString());

		if (mSign)
			throw new IllegalStateException("still in signature mode");
		if (mContent)
			throw new IllegalStateException("still in content mode");
		if (!mEnteredContent)
			throw new IllegalStateException("never entered content mode");
		return signed;
	}

	/*
	 * file i/o
	 */

	public static byte[] readBytes(File f) throws IOException {
		FileInputStream fis = new FileInputStream(f);
		FileChannel fChannel = fis.getChannel();
		byte[] barray = new byte[(int) f.length()];
		ByteBuffer bb = ByteBuffer.wrap(barray);
		//bb.order(ByteOrder.LITTLE_ENDIAN);
		fChannel.read(bb);
		fChannel.close();
		fis.close();
		return bb.array();
	}

	public static String read(String first, String... restOfPath) throws IOException {
		return read(file(first, restOfPath));
	}

	public static File file(String first, String... restOfPath) {
		File f = new File(first);
		for (String n : restOfPath)
			f = new File(f, n);
		return f;
	}

	public static String read(File file) throws IOException {
		return new String(readBytes(file), UTF8);
	}

	/*
	 * create parent directories(if needed) before write
	 */
	public static void writeDirs(File file, byte[] content) throws IOException {
		file.getParentFile().mkdirs();
		FileOutputStream fos = new FileOutputStream(file);
		fos.write(content);
		fos.flush();
		fos.close();
	}

	public static void writeDirs(File file, String content) throws IOException {
		writeDirs(file, content.getBytes(UTF8));
	}
}
