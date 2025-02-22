/*
 * Copyright 2020 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package tech.pegasys.web3signer.core.multikey.metadata.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import tech.pegasys.teku.bls.BLSKeyPair;
import tech.pegasys.teku.bls.BLSSecretKey;
import tech.pegasys.web3signer.core.config.AzureAuthenticationMode;
import tech.pegasys.web3signer.core.multikey.metadata.AzureSecretSigningMetadata;
import tech.pegasys.web3signer.core.multikey.metadata.BlsArtifactSignerFactory;
import tech.pegasys.web3signer.core.multikey.metadata.FileKeyStoreMetadata;
import tech.pegasys.web3signer.core.multikey.metadata.FileRawSigningMetadata;
import tech.pegasys.web3signer.core.multikey.metadata.SignerOrigin;
import tech.pegasys.web3signer.core.multikey.metadata.SigningMetadataException;
import tech.pegasys.web3signer.core.signing.ArtifactSigner;
import tech.pegasys.web3signer.core.signing.BlsArtifactSigner;
import tech.pegasys.web3signer.core.signing.KeyType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class YamlSignerParserTest {

  private static final ObjectMapper YAML_OBJECT_MAPPER = new ObjectMapper(new YAMLFactory());
  private static final String PRIVATE_KEY =
      "3ee2224386c82ffea477e2adf28a2929f5c349165a4196158c7f3a2ecca40f35";

  @TempDir Path configDir;
  @Mock private BlsArtifactSignerFactory blsArtifactSignerFactory;
  @Mock private BlsArtifactSignerFactory otherBlsArtifactSignerFactory;

  private YamlSignerParser signerParser;

  @BeforeEach
  public void setup() {
    Mockito.reset();
    signerParser =
        new YamlSignerParser(List.of(blsArtifactSignerFactory, otherBlsArtifactSignerFactory));
    lenient().when(blsArtifactSignerFactory.getKeyType()).thenReturn(KeyType.BLS);
    lenient().when(otherBlsArtifactSignerFactory.getKeyType()).thenReturn(KeyType.SECP256K1);
  }

  @Test
  void metaDataInfoWithUnknownTypeFails() throws IOException {
    final String yamlMetadata = YAML_OBJECT_MAPPER.writeValueAsString(Map.of("type", "unknown"));
    assertThatThrownBy(() -> signerParser.parse(yamlMetadata))
        .isInstanceOf(SigningMetadataException.class)
        .hasMessageStartingWith("Invalid signing metadata file");
  }

  @Test
  void metaDataInfoWithMissingTypeFails() throws IOException {
    final String yamlMetadata = YAML_OBJECT_MAPPER.writeValueAsString(Map.of());

    assertThatThrownBy(() -> signerParser.parse(yamlMetadata))
        .isInstanceOf(SigningMetadataException.class)
        .hasMessageStartingWith("Invalid signing metadata file");
  }

  @Test
  void unencryptedMetaDataInfoWithMissingPrivateKeyFails() throws IOException {
    final String yamlMetadata = YAML_OBJECT_MAPPER.writeValueAsString(Map.of("type", "file-raw"));

    assertThatThrownBy(() -> signerParser.parse(yamlMetadata))
        .isInstanceOf(SigningMetadataException.class)
        .hasMessageStartingWith("Invalid signing metadata file format");
  }

  @Test
  void unencryptedMetaDataInfoWithInvalidHexEncodingForPrivateKeyFails() throws IOException {
    final Map<String, String> unencryptedKeyMetadataFile = new HashMap<>();
    unencryptedKeyMetadataFile.put("type", "file-raw");
    unencryptedKeyMetadataFile.put("privateKey", "NO_HEX_VALUE");
    final String yamlMetadata = YAML_OBJECT_MAPPER.writeValueAsString(unencryptedKeyMetadataFile);

    assertThatThrownBy(() -> signerParser.parse(yamlMetadata))
        .isInstanceOf(SigningMetadataException.class)
        .hasMessageStartingWith("Invalid signing metadata file format");
  }

  @Test
  void unencryptedMetaDataInfoWithPrivateKeyReturnsMetadata() throws IOException {
    final ArtifactSigner artifactSigner =
        new BlsArtifactSigner(
            new BLSKeyPair(BLSSecretKey.fromBytes(Bytes32.fromHexString(PRIVATE_KEY))),
            SignerOrigin.FILE_RAW);
    when(blsArtifactSignerFactory.create(any(FileRawSigningMetadata.class)))
        .thenReturn(artifactSigner);

    final Map<String, String> unencryptedKeyMetadataFile = new HashMap<>();
    unencryptedKeyMetadataFile.put("type", "file-raw");
    unencryptedKeyMetadataFile.put("privateKey", PRIVATE_KEY);
    final String yamlMetadata = YAML_OBJECT_MAPPER.writeValueAsString(unencryptedKeyMetadataFile);

    final List<ArtifactSigner> result = signerParser.parse(yamlMetadata);

    assertThat(result).containsOnly(artifactSigner);
    verify(blsArtifactSignerFactory).create(hasPrivateKey(PRIVATE_KEY));
  }

  @Test
  void unencryptedMetaDataInfoWith0xPrefixPrivateKeyReturnsMetadata() throws IOException {
    final ArtifactSigner artifactSigner =
        new BlsArtifactSigner(
            new BLSKeyPair(BLSSecretKey.fromBytes(Bytes32.fromHexString(PRIVATE_KEY))),
            SignerOrigin.FILE_RAW);
    when(blsArtifactSignerFactory.create(any(FileRawSigningMetadata.class)))
        .thenReturn(artifactSigner);

    final Map<String, String> unencryptedKeyMetadataFile = new HashMap<>();
    unencryptedKeyMetadataFile.put("type", "file-raw");
    unencryptedKeyMetadataFile.put("privateKey", "0x" + PRIVATE_KEY);
    final String yamlMetadata = YAML_OBJECT_MAPPER.writeValueAsString(unencryptedKeyMetadataFile);

    final List<ArtifactSigner> result = signerParser.parse(yamlMetadata);

    assertThat(result).containsOnly(artifactSigner);
    verify(blsArtifactSignerFactory).create(hasPrivateKey(PRIVATE_KEY));
  }

  @Test
  void keyStoreMetaDataInfoWithMissingAllFilesFails() throws IOException {
    final String yamlMetadata =
        YAML_OBJECT_MAPPER.writeValueAsString(Map.of("type", "file-keystore"));

    assertThatThrownBy(() -> signerParser.parse(yamlMetadata))
        .isInstanceOf(SigningMetadataException.class)
        .hasMessageStartingWith("Invalid signing metadata file format");
  }

  @Test
  void keyStoreMetaDataInfoWithMissingKeystoreFilesFails() throws IOException {
    final Path passwordFile = configDir.resolve("keystore.password");

    final Map<String, String> keystoreMetadataFile = new HashMap<>();
    keystoreMetadataFile.put("type", "file-keystore");
    keystoreMetadataFile.put("keystorePasswordFile", passwordFile.toString());
    final String yamlMetadata = YAML_OBJECT_MAPPER.writeValueAsString(keystoreMetadataFile);

    assertThatThrownBy(() -> signerParser.parse(yamlMetadata))
        .isInstanceOf(SigningMetadataException.class)
        .hasMessageStartingWith("Invalid signing metadata file format");
  }

  @Test
  void keyStoreMetaDataInfoWithMissingPasswordFilesFails() throws IOException {
    final Path keystoreFile = configDir.resolve("keystore.json");

    final Map<String, String> keystoreMetadataFile = new HashMap<>();
    keystoreMetadataFile.put("type", "file-keystore");
    keystoreMetadataFile.put("keystoreFile", keystoreFile.toString());
    final String yamlMetadata = YAML_OBJECT_MAPPER.writeValueAsString(keystoreMetadataFile);

    assertThatThrownBy(() -> signerParser.parse(yamlMetadata))
        .isInstanceOf(SigningMetadataException.class)
        .hasMessageStartingWith("Invalid signing metadata file format");
  }

  @Test
  void keyStoreMetaDataInfoReturnsMetadata() throws IOException {
    final BlsArtifactSigner artifactSigner =
        new BlsArtifactSigner(
            new BLSKeyPair(BLSSecretKey.fromBytes(Bytes32.fromHexString(PRIVATE_KEY))),
            SignerOrigin.FILE_KEYSTORE);
    when(blsArtifactSignerFactory.create(any(FileKeyStoreMetadata.class)))
        .thenReturn(artifactSigner);

    final Path keystoreFile = configDir.resolve("keystore.json");
    final Path passwordFile = configDir.resolve("keystore.password");

    final Map<String, String> keystoreMetadataFile = new HashMap<>();
    keystoreMetadataFile.put("type", "file-keystore");
    keystoreMetadataFile.put("keystoreFile", keystoreFile.toString());
    keystoreMetadataFile.put("keystorePasswordFile", passwordFile.toString());
    final String yamlMetadata = YAML_OBJECT_MAPPER.writeValueAsString(keystoreMetadataFile);

    final List<ArtifactSigner> result = signerParser.parse(yamlMetadata);
    assertThat(result).containsOnly(artifactSigner);
    verify(blsArtifactSignerFactory).create(hasKeystoreAndPasswordFile(keystoreFile, passwordFile));
  }

  @Test
  void aSignerIsCreatedForEachMatchingFactory() throws IOException {
    lenient().when(otherBlsArtifactSignerFactory.getKeyType()).thenReturn(KeyType.BLS);
    final Map<String, String> unencryptedKeyMetadataFile = new HashMap<>();
    unencryptedKeyMetadataFile.put("type", "file-raw");
    unencryptedKeyMetadataFile.put("privateKey", "0x" + PRIVATE_KEY);
    final String yamlMetadata = YAML_OBJECT_MAPPER.writeValueAsString(unencryptedKeyMetadataFile);

    final List<ArtifactSigner> result = signerParser.parse(yamlMetadata);
    assertThat(result).hasSize(2);
  }

  private FileKeyStoreMetadata hasKeystoreAndPasswordFile(
      final Path keystoreFile, final Path passwordFile) {
    return argThat(
        (FileKeyStoreMetadata m) ->
            m.getKeystoreFile().equals(keystoreFile)
                && m.getKeystorePasswordFile().equals(passwordFile));
  }

  private FileRawSigningMetadata hasPrivateKey(final String privateKey) {
    return argThat(
        (FileRawSigningMetadata m) ->
            m.getPrivateKeyBytes().equals(Bytes32.fromHexString(privateKey)));
  }

  @Test
  void azureSecretMetadataInfoReturnsMetadata() throws IOException {
    final BlsArtifactSigner artifactSigner =
        new BlsArtifactSigner(
            new BLSKeyPair(BLSSecretKey.fromBytes(Bytes32.fromHexString(PRIVATE_KEY))),
            SignerOrigin.AZURE);
    when(blsArtifactSignerFactory.create(any(AzureSecretSigningMetadata.class)))
        .thenReturn(artifactSigner);

    final Map<String, String> azureMetaDataMap = new HashMap<>();
    azureMetaDataMap.put("type", "azure-secret");
    azureMetaDataMap.put("clientId", "sample-client-id");
    azureMetaDataMap.put("clientSecret", "sample-client-secret");
    azureMetaDataMap.put("tenantId", "sample-tenant-id");
    azureMetaDataMap.put("vaultName", "sample-vault-name");
    azureMetaDataMap.put("secretName", "TEST-KEY");
    azureMetaDataMap.put("keyType", "BLS");
    final String yamlMetadata = YAML_OBJECT_MAPPER.writeValueAsString(azureMetaDataMap);

    final List<ArtifactSigner> result = signerParser.parse(yamlMetadata);
    assertThat(result).containsOnly(artifactSigner);
    verify(blsArtifactSignerFactory)
        .create(hasCorrectAzureMetadataArguments(AzureAuthenticationMode.CLIENT_SECRET));
  }

  @ParameterizedTest
  @EnumSource(AzureAuthenticationMode.class)
  void azureSecretMetadataWithAuthenticationModeReturnsMetadata(
      final AzureAuthenticationMode authenticationMode) throws IOException {
    final BlsArtifactSigner artifactSigner =
        new BlsArtifactSigner(
            new BLSKeyPair(BLSSecretKey.fromBytes(Bytes32.fromHexString(PRIVATE_KEY))),
            SignerOrigin.AZURE);
    when(blsArtifactSignerFactory.create(any(AzureSecretSigningMetadata.class)))
        .thenReturn(artifactSigner);

    final Map<String, String> azureMetaDataMap = new HashMap<>();
    azureMetaDataMap.put("type", "azure-secret");
    azureMetaDataMap.put("clientId", "sample-client-id");
    azureMetaDataMap.put("clientSecret", "sample-client-secret");
    azureMetaDataMap.put("tenantId", "sample-tenant-id");
    azureMetaDataMap.put("vaultName", "sample-vault-name");
    azureMetaDataMap.put("secretName", "TEST-KEY");
    azureMetaDataMap.put("authenticationMode", authenticationMode.name());
    azureMetaDataMap.put("keyType", "BLS");
    final String yamlMetadata = YAML_OBJECT_MAPPER.writeValueAsString(azureMetaDataMap);

    final List<ArtifactSigner> result = signerParser.parse(yamlMetadata);
    assertThat(result).containsOnly(artifactSigner);
    verify(blsArtifactSignerFactory).create(hasCorrectAzureMetadataArguments(authenticationMode));
  }

  @Test
  void azureSecretMetadataWithSystemAssignedManagedIdentityReturnsMetadata() throws IOException {
    final BlsArtifactSigner artifactSigner =
        new BlsArtifactSigner(
            new BLSKeyPair(BLSSecretKey.fromBytes(Bytes32.fromHexString(PRIVATE_KEY))),
            SignerOrigin.AZURE);
    when(blsArtifactSignerFactory.create(any(AzureSecretSigningMetadata.class)))
        .thenReturn(artifactSigner);

    final Map<String, String> azureMetaDataMap = new HashMap<>();
    azureMetaDataMap.put("type", "azure-secret");
    azureMetaDataMap.put("vaultName", "sample-vault-name");
    azureMetaDataMap.put("secretName", "TEST-KEY");
    azureMetaDataMap.put(
        "authenticationMode", AzureAuthenticationMode.SYSTEM_ASSIGNED_MANAGED_IDENTITY.name());
    final String yamlMetadata = YAML_OBJECT_MAPPER.writeValueAsString(azureMetaDataMap);

    final List<ArtifactSigner> result = signerParser.parse(yamlMetadata);
    assertThat(result).containsOnly(artifactSigner);
    verify(blsArtifactSignerFactory)
        .create(hasCorrectAzureManagedIdentityMinimalMetadataArguments());
  }

  @Test
  void validationFailsForInvalidAzureAuthenticationMode() throws IOException {
    final Map<String, String> azureMetaDataMap = new HashMap<>();
    azureMetaDataMap.put("type", "azure-secret");
    azureMetaDataMap.put("clientId", "sample-client-id");
    azureMetaDataMap.put("clientSecret", "sample-client-secret");
    azureMetaDataMap.put("tenantId", "sample-tenant-id");
    azureMetaDataMap.put("vaultName", "sample-vault-name");
    azureMetaDataMap.put("secretName", "TEST-KEY");
    azureMetaDataMap.put("authenticationMode", "invalid_auth_mode");
    azureMetaDataMap.put("keyType", "BLS");
    final String yamlMetadata = YAML_OBJECT_MAPPER.writeValueAsString(azureMetaDataMap);

    assertThatExceptionOfType(SigningMetadataException.class)
        .isThrownBy(() -> signerParser.parse(yamlMetadata))
        .withMessage("Invalid signing metadata file format");
  }

  @Test
  void validationFailsForMissingRequiredOptionsForClientSecretMode() throws IOException {
    final Map<String, String> azureMetaDataMap = new HashMap<>();
    azureMetaDataMap.put("type", "azure-secret");
    azureMetaDataMap.put("vaultName", "sample-vault-name");
    azureMetaDataMap.put("secretName", "TEST-KEY");
    azureMetaDataMap.put("authenticationMode", AzureAuthenticationMode.CLIENT_SECRET.name());
    final String yamlMetadata = YAML_OBJECT_MAPPER.writeValueAsString(azureMetaDataMap);

    assertThatExceptionOfType(SigningMetadataException.class)
        .isThrownBy(() -> signerParser.parse(yamlMetadata))
        .withMessage("Invalid signing metadata file format");
  }

  private AzureSecretSigningMetadata hasCorrectAzureMetadataArguments(
      final AzureAuthenticationMode authenticationMode) {
    return argThat(
        (AzureSecretSigningMetadata m) ->
            m.getClientId().equals("sample-client-id")
                && m.getClientSecret().equals("sample-client-secret")
                && m.getTenantId().equals("sample-tenant-id")
                && m.getKeyVaultName().equals("sample-vault-name")
                && m.getSecretName().equals("TEST-KEY")
                && m.getAuthenticationMode().equals(authenticationMode));
  }

  private AzureSecretSigningMetadata hasCorrectAzureManagedIdentityMinimalMetadataArguments() {
    return argThat(
        (AzureSecretSigningMetadata m) ->
            m.getClientId() == null
                && m.getClientSecret() == null
                && m.getTenantId() == null
                && m.getKeyVaultName().equals("sample-vault-name")
                && m.getSecretName().equals("TEST-KEY")
                && m.getAuthenticationMode()
                    .equals(AzureAuthenticationMode.SYSTEM_ASSIGNED_MANAGED_IDENTITY));
  }
}
