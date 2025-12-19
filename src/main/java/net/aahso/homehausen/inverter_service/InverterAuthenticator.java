package net.aahso.homehausen.inverter_service;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;

import java.util.Random;
import java.util.Base64;

import java.security.SecureRandom;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.InvalidKeyException;
import java.security.InvalidAlgorithmParameterException;
import java.security.spec.InvalidKeySpecException;

import javax.crypto.Mac;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.GCMParameterSpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;


public class InverterAuthenticator {
	
    private static final String USER_TYPE = "user";
    private static final String AUTH_START = "/auth/start";
    private static final String AUTH_FINISH = "/auth/finish";
    private static final String AUTH_CREATE_SESSION = "/auth/create_session";
    private static final int    AES_GCM_TAG_LENGTH = 128; // bit count


    /* authenticate on Inverter API */
    /* returns sessionID */
    public static String authenticate(WebClient webClient, String passwordFile) {

        String password = getPW(passwordFile);

    	Logger logger = LoggerFactory.getLogger(InverterAuthenticator.class);
        ObjectMapper mapper = new ObjectMapper();
    	String responseStr = null;

        String clientNonce = createClientNonce();
        JsonNode authMeNode = mapper.createObjectNode();
        ((ObjectNode) authMeNode).put("username", USER_TYPE);
        ((ObjectNode) authMeNode).put("nonce", clientNonce);
        //System.out.println("before call (Jack) - " + authMeNode.toString());

    	// start auth process
        try {
        	responseStr = webClient.post()
				.uri(AUTH_START)
				.bodyValue(authMeNode.toString())
				.retrieve()
				.onStatus(s -> !(s.is2xxSuccessful()), response -> {
			        return Mono.error(new Exception("Error "+response.statusCode().value()+" in "+AUTH_START));
			      })
				.bodyToMono(String.class)
				.block();
        }
    	catch (Exception e) {
    		e.printStackTrace();
    		logger.error("Error in "+AUTH_START);
    		return null;
    	}

        JsonNode authMeResponseObject = null;
        try {
        	authMeResponseObject = mapper.readTree(responseStr);
            //System.out.println("Result from "+AUTH_START+" jack: " + authMeResponseObject.toString());
        }
        catch(Exception e) {
        	logger.error("ERROR in START JSON --- " + e.getMessage());
        	return null;
        }
        
        // Extract information from the response
        int rounds = authMeResponseObject.get("rounds").asInt();
        String salt = authMeResponseObject.get("salt").asText();
        String serverNonce = authMeResponseObject.get("nonce").asText();
        String transactionId = authMeResponseObject.get("transactionId").asText();
        
        // Do the cryptography stuff (magic happens here)
        byte[] saltedPasswort;
        byte[] clientKey;
        byte[] serverKey;
        byte[] storedKey;
        byte[] clientSignature;
        byte[] serverSignature;
        String authMessage;
        try {
            saltedPasswort = getPBKDF2Hash(password, Base64.getDecoder().decode(salt), rounds);
            clientKey = getHMACSha256(saltedPasswort, "Client Key");
            serverKey = getHMACSha256(saltedPasswort, "Server Key");
            storedKey = getSha256Hash(clientKey);
            authMessage = String.format("n=%s,r=%s,r=%s,s=%s,i=%d,c=biws,r=%s", USER_TYPE, clientNonce, serverNonce,
                    salt, rounds, serverNonce);
            clientSignature = getHMACSha256(storedKey, authMessage);
            serverSignature = getHMACSha256(serverKey, authMessage);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | InvalidKeyException | IllegalStateException e2) {
            logger.error("Exception in Crypto: " + e2);
            return null;
        }
        String clientProof = createClientProof(clientSignature, clientKey);

        // Perform step 2 of the authentication
        JsonNode authFinishNode = mapper.createObjectNode();
        ((ObjectNode) authFinishNode).put("transactionId", transactionId);
        ((ObjectNode) authFinishNode).put("proof", clientProof);
        
        try {
        	responseStr = webClient.post()
        			.uri(AUTH_FINISH)
				.bodyValue(authFinishNode.toString())
				.retrieve()
				.onStatus(s -> !(s.is2xxSuccessful()), response -> {
			        return Mono.error(new Exception("Error "+response.statusCode().value()+" in "+AUTH_FINISH));
			      })
				.bodyToMono(String.class)
				.block();
	    }
		catch (Exception e) {
			e.printStackTrace();
			logger.error("Error in "+AUTH_FINISH);
			return null;
		}

        JsonNode authFinishResponseObject = null;
        try {
        	authFinishResponseObject = mapper.readTree(responseStr);
        }
        catch(Exception e) {
        	logger.error("ERROR in FINISH JSON --- " + e.getMessage());
        	return null;
        }
        
        // Extract information from the response
        byte[] signature = Base64.getDecoder().decode(authFinishResponseObject.get("signature").asText());
        String token = authFinishResponseObject.get("token").asText();

        // Validate provided signature against calculated signature
        if (!java.util.Arrays.equals(serverSignature, signature)) {
            logger.error("Error in Authentication");
            return null;
        }

        // Calculate protocol key
        SecretKeySpec signingKey = new SecretKeySpec(storedKey, "HMACSHA256");
        Mac mac;
        byte[] protocolKeyHMAC;
        try {
            mac = Mac.getInstance("HMACSHA256");
            mac.init(signingKey);
            mac.update("Session Key".getBytes());
            mac.update(authMessage.getBytes());
            mac.update(clientKey);
            protocolKeyHMAC = mac.doFinal();
        } catch (NoSuchAlgorithmException | InvalidKeyException e1) {
            // Since the necessary libraries are provided, this should not happen
            logger.error("Error that never happens");
            return null;
        }
       
        byte[] data;
        byte[] iv;

        // AES GCM stuff
        iv = new byte[16];

        new SecureRandom().nextBytes(iv);

        SecretKeySpec skeySpec = new SecretKeySpec(protocolKeyHMAC, "AES");
        GCMParameterSpec param = new GCMParameterSpec(protocolKeyHMAC.length * 8 - AES_GCM_TAG_LENGTH, iv);

        Cipher cipher;
        try {
            cipher = Cipher.getInstance("AES_256/GCM/NOPADDING");
            cipher.init(Cipher.ENCRYPT_MODE, skeySpec, param);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException
                | InvalidAlgorithmParameterException e1) {
            // The java installation does not support AES encryption in GCM mode
            logger.error("AES encryption in GCM mode not supported");
            return null;
        }
        try {
            data = cipher.doFinal(token.getBytes("UTF-8"));
        } catch (IllegalBlockSizeException | BadPaddingException | UnsupportedEncodingException e1) {
            // No JSON answer received
            logger.error("No JSON answer");
            return null;
        }

        byte[] ciphertext = new byte[data.length - AES_GCM_TAG_LENGTH / 8];
        byte[] gcmTag = new byte[AES_GCM_TAG_LENGTH / 8];
        System.arraycopy(data, 0, ciphertext, 0, data.length - AES_GCM_TAG_LENGTH / 8);
        System.arraycopy(data, data.length - AES_GCM_TAG_LENGTH / 8, gcmTag, 0, AES_GCM_TAG_LENGTH / 8);

        // prepare input for session API
        JsonNode createSessionNode = mapper.createObjectNode();
        ((ObjectNode) createSessionNode).put("transactionId", transactionId);
        ((ObjectNode) createSessionNode).put("iv", Base64.getEncoder().encodeToString(iv));
        ((ObjectNode) createSessionNode).put("tag", Base64.getEncoder().encodeToString(gcmTag));
        ((ObjectNode) createSessionNode).put("payload", Base64.getEncoder().encodeToString(ciphertext));
        
        // finally create the session for further communication !
		try {
			responseStr = webClient.post()
					.uri(AUTH_CREATE_SESSION)
					.bodyValue(createSessionNode.toString())
					.retrieve()
					.onStatus(s -> !(s.is2xxSuccessful()), response -> {
				        return Mono.error(new Exception("Error "+response.statusCode().value()+" in "+AUTH_CREATE_SESSION));
				      })
					.bodyToMono(String.class)
					.block();
		}
		catch (Exception e) {
			e.printStackTrace();
			logger.error("Error in "+AUTH_CREATE_SESSION);
			return null;
		}

        JsonNode createSessionResponseObject = null;
        try {
        	createSessionResponseObject = mapper.readTree(responseStr);
        }
        catch(Exception e) {
        	logger.error("ERROR in SESSION JSON --- " + e.getMessage());
        	return null;
        }
        
         // Extract information from the response
        String sessionId = createSessionResponseObject.get("sessionId").asText();
        logger.info("Successfully generated a Session ID.");
        
        return sessionId;
        
    }

    /* read password from file */
    private static String getPW(String file) {

        String line = null;

        try {
            BufferedReader reader = new BufferedReader(new FileReader(file));
            line = reader.readLine();
            // System.out.println(line);
            reader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return line;
    }

    /**
     * Create the nonce (numbers used once) for the client for communication
     *
     * @return nonce
     */
    private static String createClientNonce() {
        Random generator = new Random();

        // Randomize the random generator
        byte[] randomizeArray = new byte[1024];
        generator.nextBytes(randomizeArray);

        // 3 words of 4 bytes are required for the handshake
        byte[] nonceArray = new byte[12];
        generator.nextBytes(nonceArray);

        // return the base64 encoded value of the random words
        return Base64.getMimeEncoder().encodeToString(nonceArray);
    }

    /**
     * Create the PBKDF2 hash
     *
     * @param password password
     * @param salt     salt
     * @param rounds   rounds
     * @return hash
     * @throws NoSuchAlgorithmException if PBKDF2WithHmacSHA256 is not supported
     * @throws InvalidKeySpecException  if the key specification is not supported
     */
    static byte[] getPBKDF2Hash(String password, byte[] salt, int rounds)
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, rounds, 256);
        SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return skf.generateSecret(spec).getEncoded();
    }

    /**
     * This method generates the HMACSha256 encrypted value of the given value
     *
     * @param password       Password used for encryption
     * @param valueToEncrypt value to encrypt
     * @return encrypted value
     * @throws InvalidKeyException      thrown if the key generated from the password is invalid
     * @throws NoSuchAlgorithmException thrown if HMAC SHA 256 is not supported
     */
    static byte[] getHMACSha256(byte[] password, String valueToEncrypt)
            throws InvalidKeyException, NoSuchAlgorithmException {
        SecretKeySpec signingKey = new SecretKeySpec(password, "HMACSHA256");
        Mac mac = Mac.getInstance("HMACSHA256");
        mac.init(signingKey);
        mac.update(valueToEncrypt.getBytes());
        return mac.doFinal();
    }

    /**
     * Create the SHA256 hash value for the given byte array
     *
     * @param valueToHash byte array to get the hash value for
     * @return the hash value
     * @throws NoSuchAlgorithmException if SHA256 is not supported
     */
    static byte[] getSha256Hash(byte[] valueToHash) throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256").digest(valueToHash);
    }

    /**
     * This methods generates the client proof.
     * It is calculated as XOR between the {@link clientSignature} and the {@link serverSignature}
     *
     * @param clientSignature client signature
     * @param serverSignature server signature
     * @return client proof
     */
    static String createClientProof(byte[] clientSignature, byte[] serverSignature) {
        byte[] result = new byte[clientSignature.length];
        for (int i = 0; i < clientSignature.length; i++) {
            result[i] = (byte) (0xff & (clientSignature[i] ^ serverSignature[i]));
        }
        return Base64.getEncoder().encodeToString(result);
    }
    
}
