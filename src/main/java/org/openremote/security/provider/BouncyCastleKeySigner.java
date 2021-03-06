/*
 * OpenRemote, the Home of the Digital Home.
 * Copyright 2008-2014, OpenRemote Inc.
 *
 * See the contributors.txt file in the distribution for a
 * full listing of individual contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.openremote.security.provider;

import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.math.BigInteger;
import java.text.DecimalFormat;
import java.util.Date;
import java.util.UUID;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.openremote.security.KeySigner;
import org.openremote.security.SecurityProvider;


/**
 * This is an implementation of {@link org.openremote.security.KeySigner} and can be used
 * to sign public keys. Signatures are created as X.509 Version 3 key certificates. <p>
 *
 * This implementation requires that a BouncyCastle security provider has been added to the
 * Java VM runtime.
 *
 * @see org.openremote.security.KeySigner
 *
 * @see java.security.Security#addProvider(java.security.Provider)
 * @see java.security.cert.X509Certificate
 *
 * @see org.bouncycastle.cert.X509v3CertificateBuilder;
 * @see org.bouncycastle.cert.X509CertificateHolder;
 * @see org.bouncycastle.operator.ContentSigner;
 *
 * @author <a href = "mailto:juha@openremote.org">Juha Lindfors</a>
 */
public class BouncyCastleKeySigner implements KeySigner
{

  /**
   * Creates a public key certificate that is signed with a given private signing key. <p>
   *
   * Java encryption APIs in many places require keys that are signed with trusted authorities,
   * even in cases where third party signature authority adds little value, for example when
   * using asymmetric key encryption between two trusted services. This implementation can be used
   * to create public key certificates for those cases. <p>
   *
   * Key, signature and certificate parameters can be controlled with the
   * {@link KeySigner.Configuration} instance passed as a parameter to this call.  <p>
   *
   * @see     KeySigner.Configuration
   *
   * @param   config
   *            configuration for the certificate: issuer, validity, signature algorithm, etc.
   *
   * @return  a X.509 v3 public key certificate
   *
   * @throws  SigningException
   *            if creating a certificate fails for any reason
   */
  @Override public X509Certificate signPublicKey(Configuration config)
      throws SigningException
  {
    if (config == null)
    {
      throw new SigningException("Implementation error: null certificate configuration.");
    }

    try
    {
      // Create BouncyCastle X.509 certificate builder...

      X509v3CertificateBuilder certBuilder = createCertificateBuilder(config);

      // Create BouncyCastle signer using the keypair's private signing key. The signature
      // algorithm used is defined by the configuration instance...

      ContentSigner signer = createContentSigner(config);

      // Sign the key...

      return signPublicKey(certBuilder, signer);
    }

    catch (IllegalStateException exception)
    {
      // Incorrect API usage, most likely missing fields in certificate generator...

      throw new SigningException(
          "Implementation Error -- Cannot create certificate: {0}",
          exception,
          exception.getMessage()
      );
    }
  }


  // Private Instance Methods ---------------------------------------------------------------------

  /**
   * Creates a BouncyCastle X.509 V3 certificate builder. The certificate is configured with
   * X.500 names given in the configuration for the issuer and subject and a given validity
   * period for the certificate. The certificate's serial number is generated as an unique
   * 40 character integer value, see {@link #generateUniqueSerial()} for details.
   *
   * @param config
   *          configuration for the X.509 certificate builder
   *
   * @return  BouncyCastle certificate builder instance
   */
  private X509v3CertificateBuilder createCertificateBuilder(Configuration config)
  {
    X500Name issuerName = new X500Name(config.getIssuer().toX500Name());
    X500Name subjectName = new X500Name(config.getSubject().toX500Name());

    // Get configured certificate validity dates...

    Date notBefore = new Date(config.getValidityPeriod().getNotBeforeDate().getTime());
    Date notAfter = new Date(config.getValidityPeriod().getNotAfterDate().getTime());

    // BouncyCastle API to build a certificate with the public key, issuer and subject,
    // validity dates and a serial number...

    return new JcaX509v3CertificateBuilder(
        issuerName,
        new BigInteger(generateUniqueSerial()),
        notBefore,
        notAfter,
        subjectName,
        config.getPublicKey()
    );
  }

  /**
   * Creates a BouncyCastle content signer that we can use to sign the X.509 certificate
   * information.
   *
   * @param config
   *          configuration instance containing the signature algorithm and the private
   *          singing key used in signing the public key
   *
   * @return
   *          BouncyCastle content signer instance
   *
   * @throws  SigningException
   *            if building the BouncyCastle content signer instance fails
   */
  private ContentSigner createContentSigner(Configuration config) throws SigningException
  {
    // BouncyCastle API to create a content signer for the certificate...

    JcaContentSignerBuilder contentSignerBuilder = new JcaContentSignerBuilder(
        config.getSignatureAlgorithm().toString()
    );

    // Explicitly set the security provider as BouncyCastle. The BC provider is dynamically
    // loaded into the JVM if necessary...

    contentSignerBuilder.setProvider(SecurityProvider.BC.getProviderInstance());


    // Sign the public key...

    try
    {
      return contentSignerBuilder.build(config.getPrivateSigningKey());
    }

    catch (OperatorCreationException exception)
    {
      throw new SigningException(
          "Unable to sign the certificate with the given private key : {0}",
          exception,
          exception.getMessage()
      );
    }
  }


  /**
   * Sign a public key with a given (BouncyCastle) signer and create the corresponding
   * X.509 certificate.
   *
   * @param builder
   *          BouncyCastle X.509 version 3 certificate builder instance
   *
   * @param signer
   *          BouncyCastle content signer configured with a signature hash algorithm and
   *          a private key for signing
   *
   * @return
   *          a X.509 certificate
   *
   * @throws  SigningException
   *            if signing the public key fails
   */
  private X509Certificate signPublicKey(X509v3CertificateBuilder builder, ContentSigner signer)
      throws SigningException
  {
    // Construct the certificate structure and sign with the given BC content signer...

    X509CertificateHolder certHolder = builder.build(signer);

    // Convert the BC X.509 certificate holder structure into Java Crypto Architecture
    // javax.security.cert.X509Certificate instance...

    JcaX509CertificateConverter certConverter = new JcaX509CertificateConverter();
    certConverter.setProvider(SecurityProvider.BC.getProviderInstance());

    try
    {
      return certConverter.getCertificate(certHolder);
    }

    catch (CertificateEncodingException exception)
    {
      // should only happen if the code for certificate creation is using illegal values...

      throw new SigningException(
          "Implementation Error -- Cannot create certificate : {0}",
          exception,
          exception.getMessage()
      );
    }

    catch (CertificateException exception)
    {
      // If certificate conversion from BouncyCastle X.509 to JCA X.509 certificate fails...

      throw new SigningException(
          "Certification conversion error : {0}",
          exception,
          exception.getMessage()
      );
    }
  }

  /**
   * Generates a unique 40-character serial number value.
   *
   * @return 40 character serial number string
   */
  private String generateUniqueSerial()
  {
    final BigInteger bit64 = BigInteger.ONE.shiftLeft(64);

    UUID random = UUID.randomUUID();

    BigInteger msb = BigInteger.valueOf(random.getMostSignificantBits());
    BigInteger lsb = BigInteger.valueOf(random.getLeastSignificantBits());

    // convert from signed to unsigned...

    if (msb.signum() < 0)
    {
      msb = msb.add(bit64);
    }

    if (lsb.signum() < 0)
    {
      lsb = lsb.add(bit64);
    }

    DecimalFormat decimalFormat = new DecimalFormat("00000000000000000000");
    String mostSignificantNumbers = decimalFormat.format(msb);
    String leastSignificantNumbers = decimalFormat.format(lsb);

    return mostSignificantNumbers + leastSignificantNumbers;
  }

}

