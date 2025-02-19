/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.appsearch.localstorage;

import static androidx.appsearch.localstorage.util.PrefixUtil.addPrefixToDocument;
import static androidx.appsearch.localstorage.util.PrefixUtil.createPrefix;
import static androidx.appsearch.localstorage.util.PrefixUtil.removePrefixesFromDocument;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.os.Process;

import androidx.appsearch.app.AppSearchResult;
import androidx.appsearch.app.AppSearchSchema;
import androidx.appsearch.app.GenericDocument;
import androidx.appsearch.app.SearchResult;
import androidx.appsearch.app.SearchResultPage;
import androidx.appsearch.app.SearchSpec;
import androidx.appsearch.app.SetSchemaResponse;
import androidx.appsearch.app.StorageInfo;
import androidx.appsearch.exceptions.AppSearchException;
import androidx.appsearch.localstorage.converter.GenericDocumentToProtoConverter;
import androidx.appsearch.localstorage.stats.InitializeStats;
import androidx.appsearch.localstorage.stats.OptimizeStats;
import androidx.appsearch.localstorage.util.PrefixUtil;
import androidx.collection.ArrayMap;
import androidx.collection.ArraySet;
import androidx.test.core.app.ApplicationProvider;

import com.google.android.icing.proto.DocumentProto;
import com.google.android.icing.proto.GetOptimizeInfoResultProto;
import com.google.android.icing.proto.PersistType;
import com.google.android.icing.proto.PropertyConfigProto;
import com.google.android.icing.proto.PropertyProto;
import com.google.android.icing.proto.PutResultProto;
import com.google.android.icing.proto.SchemaProto;
import com.google.android.icing.proto.SchemaTypeConfigProto;
import com.google.android.icing.proto.SearchResultProto;
import com.google.android.icing.proto.SearchSpecProto;
import com.google.android.icing.proto.StatusProto;
import com.google.android.icing.proto.StorageInfoProto;
import com.google.android.icing.proto.StringIndexingConfig;
import com.google.android.icing.proto.TermMatchType;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AppSearchImplTest {
    /**
     * Always trigger optimize in this class. OptimizeStrategy will be tested in its own test class.
     */
    private static final OptimizeStrategy ALWAYS_OPTIMIZE = optimizeInfo -> true;
    @Rule
    public TemporaryFolder mTemporaryFolder = new TemporaryFolder();
    private File mAppSearchDir;
    private AppSearchImpl mAppSearchImpl;

    @Before
    public void setUp() throws Exception {
        mAppSearchDir = mTemporaryFolder.newFolder();
        mAppSearchImpl = AppSearchImpl.create(
                mAppSearchDir,
                new UnlimitedLimitConfig(),
                /*initStatsBuilder=*/ null, ALWAYS_OPTIMIZE);
    }

    @After
    public void tearDown() {
        mAppSearchImpl.close();
    }

    /**
     * Ensure that we can rewrite an incoming schema type by adding the database as a prefix. While
     * also keeping any other existing schema types that may already be part of Icing's persisted
     * schema.
     */
    @Test
    public void testRewriteSchema_addType() throws Exception {
        SchemaProto.Builder existingSchemaBuilder = SchemaProto.newBuilder()
                .addTypes(SchemaTypeConfigProto.newBuilder()
                        .setSchemaType("package$existingDatabase/Foo").build());

        // Create a copy so we can modify it.
        List<SchemaTypeConfigProto> existingTypes =
                new ArrayList<>(existingSchemaBuilder.getTypesList());
        SchemaTypeConfigProto schemaTypeConfigProto1 = SchemaTypeConfigProto.newBuilder()
                .setSchemaType("Foo").build();
        SchemaTypeConfigProto schemaTypeConfigProto2 = SchemaTypeConfigProto.newBuilder()
                .setSchemaType("TestType")
                .addProperties(PropertyConfigProto.newBuilder()
                        .setPropertyName("subject")
                        .setDataType(PropertyConfigProto.DataType.Code.STRING)
                        .setCardinality(PropertyConfigProto.Cardinality.Code.OPTIONAL)
                        .setStringIndexingConfig(StringIndexingConfig.newBuilder()
                                .setTokenizerType(
                                        StringIndexingConfig.TokenizerType.Code.PLAIN)
                                .setTermMatchType(TermMatchType.Code.PREFIX)
                                .build()
                        ).build()
                ).addProperties(PropertyConfigProto.newBuilder()
                        .setPropertyName("link")
                        .setDataType(PropertyConfigProto.DataType.Code.DOCUMENT)
                        .setCardinality(PropertyConfigProto.Cardinality.Code.OPTIONAL)
                        .setSchemaType("RefType")
                        .build()
                ).build();
        SchemaTypeConfigProto schemaTypeConfigProto3 = SchemaTypeConfigProto.newBuilder()
                .setSchemaType("RefType").build();
        SchemaProto newSchema = SchemaProto.newBuilder()
                .addTypes(schemaTypeConfigProto1)
                .addTypes(schemaTypeConfigProto2)
                .addTypes(schemaTypeConfigProto3)
                .build();

        AppSearchImpl.RewrittenSchemaResults rewrittenSchemaResults = AppSearchImpl.rewriteSchema(
                createPrefix("package", "newDatabase"), existingSchemaBuilder,
                newSchema);

        // We rewrote all the new types that were added. And nothing was removed.
        assertThat(rewrittenSchemaResults.mRewrittenPrefixedTypes.keySet()).containsExactly(
                "package$newDatabase/Foo", "package$newDatabase/TestType",
                "package$newDatabase/RefType");
        assertThat(rewrittenSchemaResults.mRewrittenPrefixedTypes.get(
                "package$newDatabase/Foo").getSchemaType()).isEqualTo(
                "package$newDatabase/Foo");
        assertThat(rewrittenSchemaResults.mRewrittenPrefixedTypes.get(
                "package$newDatabase/TestType").getSchemaType()).isEqualTo(
                "package$newDatabase/TestType");
        assertThat(rewrittenSchemaResults.mRewrittenPrefixedTypes.get(
                "package$newDatabase/RefType").getSchemaType()).isEqualTo(
                "package$newDatabase/RefType");
        assertThat(rewrittenSchemaResults.mDeletedPrefixedTypes).isEmpty();

        SchemaProto expectedSchema = SchemaProto.newBuilder()
                .addTypes(SchemaTypeConfigProto.newBuilder()
                        .setSchemaType("package$newDatabase/Foo").build())
                .addTypes(SchemaTypeConfigProto.newBuilder()
                        .setSchemaType("package$newDatabase/TestType")
                        .addProperties(PropertyConfigProto.newBuilder()
                                .setPropertyName("subject")
                                .setDataType(PropertyConfigProto.DataType.Code.STRING)
                                .setCardinality(PropertyConfigProto.Cardinality.Code.OPTIONAL)
                                .setStringIndexingConfig(StringIndexingConfig.newBuilder()
                                        .setTokenizerType(
                                                StringIndexingConfig.TokenizerType.Code.PLAIN)
                                        .setTermMatchType(TermMatchType.Code.PREFIX)
                                        .build()
                                ).build()
                        ).addProperties(PropertyConfigProto.newBuilder()
                                .setPropertyName("link")
                                .setDataType(PropertyConfigProto.DataType.Code.DOCUMENT)
                                .setCardinality(PropertyConfigProto.Cardinality.Code.OPTIONAL)
                                .setSchemaType("package$newDatabase/RefType")
                                .build()
                        ).build())
                .addTypes(SchemaTypeConfigProto.newBuilder()
                        .setSchemaType("package$newDatabase/RefType").build())
                .build();

        existingTypes.addAll(expectedSchema.getTypesList());
        assertThat(existingSchemaBuilder.getTypesList()).containsExactlyElementsIn(existingTypes);
    }

    /**
     * Ensure that we track all types that were rewritten in the input schema. Even if they were
     * not technically "added" to the existing schema.
     */
    @Test
    public void testRewriteSchema_rewriteType() throws Exception {
        SchemaProto.Builder existingSchemaBuilder = SchemaProto.newBuilder()
                .addTypes(SchemaTypeConfigProto.newBuilder()
                        .setSchemaType("package$existingDatabase/Foo").build());

        SchemaProto newSchema = SchemaProto.newBuilder()
                .addTypes(SchemaTypeConfigProto.newBuilder()
                        .setSchemaType("Foo").build())
                .build();

        AppSearchImpl.RewrittenSchemaResults rewrittenSchemaResults = AppSearchImpl.rewriteSchema(
                createPrefix("package", "existingDatabase"), existingSchemaBuilder,
                newSchema);

        // Nothing was removed, but the method did rewrite the type name.
        assertThat(rewrittenSchemaResults.mRewrittenPrefixedTypes.keySet()).containsExactly(
                "package$existingDatabase/Foo");
        assertThat(rewrittenSchemaResults.mDeletedPrefixedTypes).isEmpty();

        // Same schema since nothing was added.
        SchemaProto expectedSchema = existingSchemaBuilder.build();
        assertThat(existingSchemaBuilder.getTypesList())
                .containsExactlyElementsIn(expectedSchema.getTypesList());
    }

    /**
     * Ensure that we track which types from the existing schema are deleted when a new schema is
     * set.
     */
    @Test
    public void testRewriteSchema_deleteType() throws Exception {
        SchemaProto.Builder existingSchemaBuilder = SchemaProto.newBuilder()
                .addTypes(SchemaTypeConfigProto.newBuilder()
                        .setSchemaType("package$existingDatabase/Foo").build());

        SchemaProto newSchema = SchemaProto.newBuilder()
                .addTypes(SchemaTypeConfigProto.newBuilder()
                        .setSchemaType("Bar").build())
                .build();

        AppSearchImpl.RewrittenSchemaResults rewrittenSchemaResults = AppSearchImpl.rewriteSchema(
                createPrefix("package", "existingDatabase"), existingSchemaBuilder,
                newSchema);

        // Bar type was rewritten, but Foo ended up being deleted since it wasn't included in the
        // new schema.
        assertThat(rewrittenSchemaResults.mRewrittenPrefixedTypes)
                .containsKey("package$existingDatabase/Bar");
        assertThat(rewrittenSchemaResults.mRewrittenPrefixedTypes.keySet().size()).isEqualTo(1);
        assertThat(rewrittenSchemaResults.mDeletedPrefixedTypes)
                .containsExactly("package$existingDatabase/Foo");

        // Same schema since nothing was added.
        SchemaProto expectedSchema = SchemaProto.newBuilder()
                .addTypes(SchemaTypeConfigProto.newBuilder()
                        .setSchemaType("package$existingDatabase/Bar").build())
                .build();

        assertThat(existingSchemaBuilder.getTypesList())
                .containsExactlyElementsIn(expectedSchema.getTypesList());
    }

    @Test
    public void testAddDocumentTypePrefix() {
        DocumentProto insideDocument = DocumentProto.newBuilder()
                .setUri("inside-id")
                .setSchema("type")
                .setNamespace("namespace")
                .build();
        DocumentProto documentProto = DocumentProto.newBuilder()
                .setUri("id")
                .setSchema("type")
                .setNamespace("namespace")
                .addProperties(PropertyProto.newBuilder().addDocumentValues(insideDocument))
                .build();

        DocumentProto expectedInsideDocument = DocumentProto.newBuilder()
                .setUri("inside-id")
                .setSchema("package$databaseName/type")
                .setNamespace("package$databaseName/namespace")
                .build();
        DocumentProto expectedDocumentProto = DocumentProto.newBuilder()
                .setUri("id")
                .setSchema("package$databaseName/type")
                .setNamespace("package$databaseName/namespace")
                .addProperties(PropertyProto.newBuilder().addDocumentValues(expectedInsideDocument))
                .build();

        DocumentProto.Builder actualDocument = documentProto.toBuilder();
        addPrefixToDocument(actualDocument, createPrefix("package",
                "databaseName"));
        assertThat(actualDocument.build()).isEqualTo(expectedDocumentProto);
    }

    @Test
    public void testRemoveDocumentTypePrefixes() throws Exception {
        DocumentProto insideDocument = DocumentProto.newBuilder()
                .setUri("inside-id")
                .setSchema("package$databaseName/type")
                .setNamespace("package$databaseName/namespace")
                .build();
        DocumentProto documentProto = DocumentProto.newBuilder()
                .setUri("id")
                .setSchema("package$databaseName/type")
                .setNamespace("package$databaseName/namespace")
                .addProperties(PropertyProto.newBuilder().addDocumentValues(insideDocument))
                .build();

        DocumentProto expectedInsideDocument = DocumentProto.newBuilder()
                .setUri("inside-id")
                .setSchema("type")
                .setNamespace("namespace")
                .build();

        DocumentProto expectedDocumentProto = DocumentProto.newBuilder()
                .setUri("id")
                .setSchema("type")
                .setNamespace("namespace")
                .addProperties(PropertyProto.newBuilder().addDocumentValues(expectedInsideDocument))
                .build();

        DocumentProto.Builder actualDocument = documentProto.toBuilder();
        assertThat(removePrefixesFromDocument(actualDocument)).isEqualTo(
                "package$databaseName/");
        assertThat(actualDocument.build()).isEqualTo(expectedDocumentProto);
    }

    @Test
    public void testRemoveDatabasesFromDocumentThrowsException() {
        // Set two different database names in the document, which should never happen
        DocumentProto documentProto = DocumentProto.newBuilder()
                .setUri("id")
                .setSchema("prefix1/type")
                .setNamespace("prefix2/namespace")
                .build();

        DocumentProto.Builder actualDocument = documentProto.toBuilder();
        AppSearchException e = assertThrows(AppSearchException.class, () ->
                removePrefixesFromDocument(actualDocument));
        assertThat(e).hasMessageThat().contains("Found unexpected multiple prefix names");
    }

    @Test
    public void testNestedRemoveDatabasesFromDocumentThrowsException() {
        // Set two different database names in the outer and inner document, which should never
        // happen.
        DocumentProto insideDocument = DocumentProto.newBuilder()
                .setUri("inside-id")
                .setSchema("prefix1/type")
                .setNamespace("prefix1/namespace")
                .build();
        DocumentProto documentProto = DocumentProto.newBuilder()
                .setUri("id")
                .setSchema("prefix2/type")
                .setNamespace("prefix2/namespace")
                .addProperties(PropertyProto.newBuilder().addDocumentValues(insideDocument))
                .build();

        DocumentProto.Builder actualDocument = documentProto.toBuilder();
        AppSearchException e = assertThrows(AppSearchException.class, () ->
                removePrefixesFromDocument(actualDocument));
        assertThat(e).hasMessageThat().contains("Found unexpected multiple prefix names");
    }

    @Test
    public void testTriggerCheckOptimizeByMutationSize() throws Exception {
        // Insert schema
        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("type").build());
        mAppSearchImpl.setSchema(
                "package",
                "database",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Insert a document and then remove it to generate garbage.
        GenericDocument document = new GenericDocument.Builder<>("namespace", "id", "type").build();
        mAppSearchImpl.putDocument("package", "database", document, /*logger=*/ null);
        mAppSearchImpl.remove("package", "database", "namespace", "id",
                /*removeStatsBuilder=*/ null);

        // Verify there is garbage documents.
        GetOptimizeInfoResultProto optimizeInfo = mAppSearchImpl.getOptimizeInfoResultLocked();
        assertThat(optimizeInfo.getOptimizableDocs()).isEqualTo(1);

        // Increase mutation counter and stop before reach the threshold
        mAppSearchImpl.checkForOptimize(AppSearchImpl.CHECK_OPTIMIZE_INTERVAL - 1,
                /*builder=*/null);

        // Verify the optimize() isn't triggered.
        optimizeInfo = mAppSearchImpl.getOptimizeInfoResultLocked();
        assertThat(optimizeInfo.getOptimizableDocs()).isEqualTo(1);

        // Increase the counter and reach the threshold, optimize() should be triggered.
        OptimizeStats.Builder builder = new OptimizeStats.Builder();
        mAppSearchImpl.checkForOptimize(/*mutateBatchSize=*/ 1, builder);

        // Verify optimize() is triggered.
        optimizeInfo = mAppSearchImpl.getOptimizeInfoResultLocked();
        assertThat(optimizeInfo.getOptimizableDocs()).isEqualTo(0);
        assertThat(optimizeInfo.getEstimatedOptimizableBytes()).isEqualTo(0);

        // Verify the stats have been set.
        OptimizeStats oStats = builder.build();
        assertThat(oStats.getOriginalDocumentCount()).isEqualTo(1);
        assertThat(oStats.getDeletedDocumentCount()).isEqualTo(1);
    }

    @Test
    public void testReset() throws Exception {
        // Insert schema
        Context context = ApplicationProvider.getApplicationContext();
        List<AppSearchSchema> schemas = ImmutableList.of(
                new AppSearchSchema.Builder("Type1").build(),
                new AppSearchSchema.Builder("Type2").build());
        mAppSearchImpl.setSchema(
                context.getPackageName(),
                "database1",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Insert a valid doc
        GenericDocument validDoc =
                new GenericDocument.Builder<>("namespace1", "id1", "Type1").build();
        mAppSearchImpl.putDocument(
                context.getPackageName(),
                "database1",
                validDoc,
                /*logger=*/null);

        // Query it via global query. We use the same code again later so this is to make sure we
        // have our global query configured right.
        SearchResultPage results = mAppSearchImpl.globalQuery(
                /*queryExpression=*/ "",
                new SearchSpec.Builder().addFilterSchemas("Type1").build(),
                context.getPackageName(),
                /*visibilityStore=*/ null,
                Process.INVALID_UID,
                /*callerHasSystemAccess=*/ false,
                /*logger=*/ null);
        assertThat(results.getResults()).hasSize(1);
        assertThat(results.getResults().get(0).getGenericDocument()).isEqualTo(validDoc);

        // Create a doc with a malformed namespace
        DocumentProto invalidDoc = DocumentProto.newBuilder()
                .setNamespace("invalidNamespace")
                .setUri("id2")
                .setSchema(context.getPackageName() + "$database1/Type1")
                .build();
        AppSearchException e = assertThrows(
                AppSearchException.class,
                () -> PrefixUtil.getPrefix(invalidDoc.getNamespace()));
        assertThat(e).hasMessageThat().isEqualTo(
                "The prefixed value \"invalidNamespace\" doesn't contain a valid database name");

        // Insert the invalid doc with an invalid namespace right into icing
        PutResultProto putResultProto = mAppSearchImpl.mIcingSearchEngineLocked.put(invalidDoc);
        assertThat(putResultProto.getStatus().getCode()).isEqualTo(StatusProto.Code.OK);

        // Initialize AppSearchImpl. This should cause a reset.
        InitializeStats.Builder initStatsBuilder = new InitializeStats.Builder();
        mAppSearchImpl.close();
        mAppSearchImpl = AppSearchImpl.create(
                mAppSearchDir, new UnlimitedLimitConfig(), initStatsBuilder, ALWAYS_OPTIMIZE);

        // Check recovery state
        InitializeStats initStats = initStatsBuilder.build();
        assertThat(initStats).isNotNull();
        assertThat(initStats.getStatusCode()).isEqualTo(AppSearchResult.RESULT_INTERNAL_ERROR);
        assertThat(initStats.hasDeSync()).isFalse();
        assertThat(initStats.getDocumentStoreRecoveryCause())
                .isEqualTo(InitializeStats.RECOVERY_CAUSE_NONE);
        assertThat(initStats.getIndexRestorationCause())
                .isEqualTo(InitializeStats.RECOVERY_CAUSE_NONE);
        assertThat(initStats.getSchemaStoreRecoveryCause())
                .isEqualTo(InitializeStats.RECOVERY_CAUSE_NONE);
        assertThat(initStats.getDocumentStoreDataStatus())
                .isEqualTo(InitializeStats.DOCUMENT_STORE_DATA_STATUS_NO_DATA_LOSS);
        assertThat(initStats.hasReset()).isTrue();
        assertThat(initStats.getResetStatusCode()).isEqualTo(AppSearchResult.RESULT_OK);

        // Make sure all our data is gone
        assertThat(mAppSearchImpl.getSchema(context.getPackageName(), "database1").getSchemas())
                .isEmpty();
        results = mAppSearchImpl.globalQuery(
                /*queryExpression=*/ "",
                new SearchSpec.Builder().addFilterSchemas("Type1").build(),
                context.getPackageName(),
                /*visibilityStore=*/ null,
                Process.INVALID_UID,
                /*callerHasSystemAccess=*/ false,
                /*logger=*/ null);
        assertThat(results.getResults()).isEmpty();

        // Make sure the index can now be used successfully
        mAppSearchImpl.setSchema(
                context.getPackageName(),
                "database1",
                Collections.singletonList(new AppSearchSchema.Builder("Type1").build()),
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Insert a valid doc
        mAppSearchImpl.putDocument(
                context.getPackageName(),
                "database1",
                validDoc,
                /*logger=*/null);

        // Query it via global query.
        results = mAppSearchImpl.globalQuery(
                /*queryExpression=*/ "",
                new SearchSpec.Builder().addFilterSchemas("Type1").build(),
                context.getPackageName(),
                /*visibilityStore=*/ null,
                Process.INVALID_UID,
                /*callerHasSystemAccess=*/ false,
                /*logger=*/ null);
        assertThat(results.getResults()).hasSize(1);
        assertThat(results.getResults().get(0).getGenericDocument()).isEqualTo(validDoc);
    }

    @Test
    public void testRewriteSearchSpec_oneInstance() throws Exception {
        SearchSpecProto.Builder searchSpecProto =
                SearchSpecProto.newBuilder().setQuery("");

        // Insert schema
        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("type").build());
        mAppSearchImpl.setSchema(
                "package",
                "database",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Insert document
        GenericDocument document = new GenericDocument.Builder<>("namespace", "id",
                "type").build();
        mAppSearchImpl.putDocument("package", "database", document, /*logger=*/ null);

        // Rewrite SearchSpec
        mAppSearchImpl.rewriteSearchSpecForPrefixesLocked(searchSpecProto,
                Collections.singleton(createPrefix("package", "database")),
                ImmutableSet.of("package$database/type"));
        assertThat(searchSpecProto.getSchemaTypeFiltersList()).containsExactly(
                "package$database/type");
        assertThat(searchSpecProto.getNamespaceFiltersList()).containsExactly(
                "package$database/namespace");
    }

    @Test
    public void testRewriteSearchSpec_twoInstances() throws Exception {
        SearchSpecProto.Builder searchSpecProto =
                SearchSpecProto.newBuilder().setQuery("");

        // Insert schema
        List<AppSearchSchema> schemas = ImmutableList.of(
                new AppSearchSchema.Builder("typeA").build(),
                new AppSearchSchema.Builder("typeB").build());
        mAppSearchImpl.setSchema(
                "package",
                "database1",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);
        mAppSearchImpl.setSchema(
                "package",
                "database2",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Insert documents
        GenericDocument document1 = new GenericDocument.Builder<>("namespace", "id",
                "typeA").build();
        mAppSearchImpl.putDocument("package", "database1", document1, /*logger=*/ null);

        GenericDocument document2 = new GenericDocument.Builder<>("namespace", "id",
                "typeB").build();
        mAppSearchImpl.putDocument("package", "database2", document2, /*logger=*/ null);

        // Rewrite SearchSpec
        mAppSearchImpl.rewriteSearchSpecForPrefixesLocked(searchSpecProto,
                ImmutableSet.of(createPrefix("package", "database1"),
                        createPrefix("package", "database2")), ImmutableSet.of(
                        "package$database1/typeA", "package$database1/typeB",
                        "package$database2/typeA", "package$database2/typeB"));
        assertThat(searchSpecProto.getSchemaTypeFiltersList()).containsExactly(
                "package$database1/typeA", "package$database1/typeB", "package$database2/typeA",
                "package$database2/typeB");
        assertThat(searchSpecProto.getNamespaceFiltersList()).containsExactly(
                "package$database1/namespace", "package$database2/namespace");
    }

    @Test
    public void testRewriteSearchSpec_ignoresSearchSpecSchemaFilters() throws Exception {
        SearchSpecProto.Builder searchSpecProto =
                SearchSpecProto.newBuilder().setQuery("").addSchemaTypeFilters("type");

        // Insert schema
        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("type").build());
        mAppSearchImpl.setSchema(
                "package",
                "database",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Insert document
        GenericDocument document = new GenericDocument.Builder<>("namespace", "id",
                "type").build();
        mAppSearchImpl.putDocument("package", "database", document, /*logger=*/ null);

        // If 'allowedPrefixedSchemas' is empty, this returns false since there's nothing to
        // search over. Despite the searchSpecProto having schema type filters.
        assertThat(mAppSearchImpl.rewriteSearchSpecForPrefixesLocked(searchSpecProto,
                Collections.singleton(createPrefix("package", "database")),
                /*allowedPrefixedSchemas=*/ Collections.emptySet())).isFalse();
    }

    @Test
    public void testQueryEmptyDatabase() throws Exception {
        SearchSpec searchSpec =
                new SearchSpec.Builder().setTermMatch(TermMatchType.Code.PREFIX_VALUE).build();
        SearchResultPage searchResultPage = mAppSearchImpl.query("package", "EmptyDatabase", "",
                searchSpec, /*logger=*/ null);
        assertThat(searchResultPage.getResults()).isEmpty();
    }

    /**
     * TODO(b/169883602): This should be an integration test at the cts-level. This is a
     * short-term test until we have official support for multiple-apps indexing at once.
     */
    @Test
    public void testQueryWithMultiplePackages_noPackageFilters() throws Exception {
        // Insert package1 schema
        List<AppSearchSchema> schema1 =
                ImmutableList.of(new AppSearchSchema.Builder("schema1").build());
        mAppSearchImpl.setSchema(
                "package1",
                "database1",
                schema1,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Insert package2 schema
        List<AppSearchSchema> schema2 =
                ImmutableList.of(new AppSearchSchema.Builder("schema2").build());
        mAppSearchImpl.setSchema(
                "package2",
                "database2",
                schema2,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Insert package1 document
        GenericDocument document = new GenericDocument.Builder<>("namespace", "id", "schema1")
                .build();
        mAppSearchImpl.putDocument("package1", "database1", document, /*logger=*/ null);

        // No query filters specified, package2 shouldn't be able to query for package1's documents.
        SearchSpec searchSpec =
                new SearchSpec.Builder().setTermMatch(TermMatchType.Code.PREFIX_VALUE).build();
        SearchResultPage searchResultPage = mAppSearchImpl.query("package2", "database2", "",
                searchSpec, /*logger=*/ null);
        assertThat(searchResultPage.getResults()).isEmpty();

        // Insert package2 document
        document = new GenericDocument.Builder<>("namespace", "id", "schema2").build();
        mAppSearchImpl.putDocument("package2", "database2", document, /*logger=*/ null);

        // No query filters specified. package2 should only get its own documents back.
        searchResultPage = mAppSearchImpl.query("package2", "database2", "", searchSpec, /*logger=
         */ null);
        assertThat(searchResultPage.getResults()).hasSize(1);
        assertThat(searchResultPage.getResults().get(0).getGenericDocument()).isEqualTo(document);
    }

    /**
     * TODO(b/169883602): This should be an integration test at the cts-level. This is a
     * short-term test until we have official support for multiple-apps indexing at once.
     */
    @Test
    public void testQueryWithMultiplePackages_withPackageFilters() throws Exception {
        // Insert package1 schema
        List<AppSearchSchema> schema1 =
                ImmutableList.of(new AppSearchSchema.Builder("schema1").build());
        mAppSearchImpl.setSchema(
                "package1",
                "database1",
                schema1,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Insert package2 schema
        List<AppSearchSchema> schema2 =
                ImmutableList.of(new AppSearchSchema.Builder("schema2").build());
        mAppSearchImpl.setSchema(
                "package2",
                "database2",
                schema2,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Insert package1 document
        GenericDocument document = new GenericDocument.Builder<>("namespace", "id",
                "schema1").build();
        mAppSearchImpl.putDocument("package1", "database1", document, /*logger=*/ null);

        // "package1" filter specified, but package2 shouldn't be able to query for package1's
        // documents.
        SearchSpec searchSpec = new SearchSpec.Builder()
                .setTermMatch(TermMatchType.Code.PREFIX_VALUE)
                .addFilterPackageNames("package1")
                .build();
        SearchResultPage searchResultPage = mAppSearchImpl.query("package2", "database2", "",
                searchSpec, /*logger=*/ null);
        assertThat(searchResultPage.getResults()).isEmpty();

        // Insert package2 document
        document = new GenericDocument.Builder<>("namespace", "id", "schema2").build();
        mAppSearchImpl.putDocument("package2", "database2", document, /*logger=*/ null);

        // "package2" filter specified, package2 should only get its own documents back.
        searchSpec = new SearchSpec.Builder()
                .setTermMatch(TermMatchType.Code.PREFIX_VALUE)
                .addFilterPackageNames("package2")
                .build();
        searchResultPage = mAppSearchImpl.query("package2", "database2", "", searchSpec, /*logger=
         */ null);
        assertThat(searchResultPage.getResults()).hasSize(1);
        assertThat(searchResultPage.getResults().get(0).getGenericDocument()).isEqualTo(document);
    }

    @Test
    public void testGlobalQueryEmptyDatabase() throws Exception {
        SearchSpec searchSpec =
                new SearchSpec.Builder().setTermMatch(TermMatchType.Code.PREFIX_VALUE).build();
        SearchResultPage searchResultPage = mAppSearchImpl.globalQuery(
                "",
                searchSpec,
                /*callerPackageName=*/ "",
                /*visibilityStore=*/ null,
                Process.INVALID_UID,
                /*callerHasSystemAccess=*/ false,
                /*logger=*/ null);
        assertThat(searchResultPage.getResults()).isEmpty();
    }

    @Test
    public void testGetNextPageToken_query() throws Exception {
        // Insert package1 schema
        List<AppSearchSchema> schema1 =
                ImmutableList.of(new AppSearchSchema.Builder("schema1").build());
        mAppSearchImpl.setSchema(
                "package1",
                "database1",
                schema1,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Insert two package1 documents
        GenericDocument document1 = new GenericDocument.Builder<>("namespace", "id1",
                "schema1").build();
        GenericDocument document2 = new GenericDocument.Builder<>("namespace", "id2",
                "schema1").build();
        mAppSearchImpl.putDocument("package1", "database1", document1, /*logger=*/ null);
        mAppSearchImpl.putDocument("package1", "database1", document2, /*logger=*/ null);

        // Query for only 1 result per page
        SearchSpec searchSpec = new SearchSpec.Builder()
                .setTermMatch(TermMatchType.Code.PREFIX_VALUE)
                .setResultCountPerPage(1)
                .build();
        SearchResultPage searchResultPage = mAppSearchImpl.query("package1", "database1", "",
                searchSpec, /*logger=*/ null);

        // Document2 will come first because it was inserted last and default return order is
        // most recent.
        assertThat(searchResultPage.getResults()).hasSize(1);
        assertThat(searchResultPage.getResults().get(0).getGenericDocument()).isEqualTo(document2);

        long nextPageToken = searchResultPage.getNextPageToken();
        searchResultPage = mAppSearchImpl.getNextPage("package1", nextPageToken,
                /*statsBuilder=*/ null);
        assertThat(searchResultPage.getResults()).hasSize(1);
        assertThat(searchResultPage.getResults().get(0).getGenericDocument()).isEqualTo(document1);
    }

    @Test
    public void testGetNextPageWithDifferentPackage_query() throws Exception {
        // Insert package1 schema
        List<AppSearchSchema> schema1 =
                ImmutableList.of(new AppSearchSchema.Builder("schema1").build());
        mAppSearchImpl.setSchema(
                "package1",
                "database1",
                schema1,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Insert two package1 documents
        GenericDocument document1 = new GenericDocument.Builder<>("namespace", "id1",
                "schema1").build();
        GenericDocument document2 = new GenericDocument.Builder<>("namespace", "id2",
                "schema1").build();
        mAppSearchImpl.putDocument("package1", "database1", document1, /*logger=*/ null);
        mAppSearchImpl.putDocument("package1", "database1", document2, /*logger=*/ null);

        // Query for only 1 result per page
        SearchSpec searchSpec = new SearchSpec.Builder()
                .setTermMatch(TermMatchType.Code.PREFIX_VALUE)
                .setResultCountPerPage(1)
                .build();
        SearchResultPage searchResultPage = mAppSearchImpl.query("package1", "database1", "",
                searchSpec, /*logger=*/ null);

        // Document2 will come first because it was inserted last and default return order is
        // most recent.
        assertThat(searchResultPage.getResults()).hasSize(1);
        assertThat(searchResultPage.getResults().get(0).getGenericDocument()).isEqualTo(document2);

        long nextPageToken = searchResultPage.getNextPageToken();

        // Try getting next page with the wrong package, package2
        AppSearchException e = assertThrows(AppSearchException.class,
                () -> mAppSearchImpl.getNextPage("package2",
                        nextPageToken, /*statsBuilder=*/ null));
        assertThat(e).hasMessageThat().contains(
                "Package \"package2\" cannot use nextPageToken: " + nextPageToken);
        assertThat(e.getResultCode()).isEqualTo(AppSearchResult.RESULT_SECURITY_ERROR);

        // Can continue getting next page for package1
        searchResultPage = mAppSearchImpl.getNextPage("package1", nextPageToken,
                /*statsBuilder=*/ null);
        assertThat(searchResultPage.getResults()).hasSize(1);
        assertThat(searchResultPage.getResults().get(0).getGenericDocument()).isEqualTo(document1);
    }

    @Test
    public void testGetNextPageToken_globalQuery() throws Exception {
        // Insert package1 schema
        List<AppSearchSchema> schema1 =
                ImmutableList.of(new AppSearchSchema.Builder("schema1").build());
        mAppSearchImpl.setSchema(
                "package1",
                "database1",
                schema1,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Insert two package1 documents
        GenericDocument document1 = new GenericDocument.Builder<>("namespace", "id1",
                "schema1").build();
        GenericDocument document2 = new GenericDocument.Builder<>("namespace", "id2",
                "schema1").build();
        mAppSearchImpl.putDocument("package1", "database1", document1, /*logger=*/ null);
        mAppSearchImpl.putDocument("package1", "database1", document2, /*logger=*/ null);

        // Query for only 1 result per page
        SearchSpec searchSpec = new SearchSpec.Builder()
                .setTermMatch(TermMatchType.Code.PREFIX_VALUE)
                .setResultCountPerPage(1)
                .build();
        SearchResultPage searchResultPage = mAppSearchImpl.globalQuery(/*queryExpression=*/ "",
                searchSpec, "package1", /*visibilityStore=*/ null, Process.myUid(),
                /*callerHasSystemAccess=*/ false, /*logger=*/ null);

        // Document2 will come first because it was inserted last and default return order is
        // most recent.
        assertThat(searchResultPage.getResults()).hasSize(1);
        assertThat(searchResultPage.getResults().get(0).getGenericDocument()).isEqualTo(document2);

        long nextPageToken = searchResultPage.getNextPageToken();
        searchResultPage = mAppSearchImpl.getNextPage("package1", nextPageToken,
                /*statsBuilder=*/ null);
        assertThat(searchResultPage.getResults()).hasSize(1);
        assertThat(searchResultPage.getResults().get(0).getGenericDocument()).isEqualTo(document1);
    }

    @Test
    public void testGetNextPageWithDifferentPackage_globalQuery() throws Exception {
        // Insert package1 schema
        List<AppSearchSchema> schema1 =
                ImmutableList.of(new AppSearchSchema.Builder("schema1").build());
        mAppSearchImpl.setSchema(
                "package1",
                "database1",
                schema1,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Insert two package1 documents
        GenericDocument document1 = new GenericDocument.Builder<>("namespace", "id1",
                "schema1").build();
        GenericDocument document2 = new GenericDocument.Builder<>("namespace", "id2",
                "schema1").build();
        mAppSearchImpl.putDocument("package1", "database1", document1, /*logger=*/ null);
        mAppSearchImpl.putDocument("package1", "database1", document2, /*logger=*/ null);

        // Query for only 1 result per page
        SearchSpec searchSpec = new SearchSpec.Builder()
                .setTermMatch(TermMatchType.Code.PREFIX_VALUE)
                .setResultCountPerPage(1)
                .build();
        SearchResultPage searchResultPage = mAppSearchImpl.globalQuery(/*queryExpression=*/ "",
                searchSpec, "package1", /*visibilityStore=*/ null, Process.myUid(),
                /*callerHasSystemAccess=*/ false, /*logger=*/ null);

        // Document2 will come first because it was inserted last and default return order is
        // most recent.
        assertThat(searchResultPage.getResults()).hasSize(1);
        assertThat(searchResultPage.getResults().get(0).getGenericDocument()).isEqualTo(document2);

        long nextPageToken = searchResultPage.getNextPageToken();

        // Try getting next page with the wrong package, package2
        AppSearchException e = assertThrows(AppSearchException.class,
                () -> mAppSearchImpl.getNextPage("package2", nextPageToken,
                        /*statsBuilder=*/ null));
        assertThat(e).hasMessageThat().contains(
                "Package \"package2\" cannot use nextPageToken: " + nextPageToken);
        assertThat(e.getResultCode()).isEqualTo(AppSearchResult.RESULT_SECURITY_ERROR);

        // Can continue getting next page for package1
        searchResultPage = mAppSearchImpl.getNextPage("package1", nextPageToken,
                /*statsBuilder=*/ null);
        assertThat(searchResultPage.getResults()).hasSize(1);
        assertThat(searchResultPage.getResults().get(0).getGenericDocument()).isEqualTo(document1);
    }

    @Test
    public void testInvalidateNextPageToken_query() throws Exception {
        // Insert package1 schema
        List<AppSearchSchema> schema1 =
                ImmutableList.of(new AppSearchSchema.Builder("schema1").build());
        mAppSearchImpl.setSchema(
                "package1",
                "database1",
                schema1,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Insert two package1 documents
        GenericDocument document1 = new GenericDocument.Builder<>("namespace", "id1",
                "schema1").build();
        GenericDocument document2 = new GenericDocument.Builder<>("namespace", "id2",
                "schema1").build();
        mAppSearchImpl.putDocument("package1", "database1", document1, /*logger=*/ null);
        mAppSearchImpl.putDocument("package1", "database1", document2, /*logger=*/ null);

        // Query for only 1 result per page
        SearchSpec searchSpec = new SearchSpec.Builder()
                .setTermMatch(TermMatchType.Code.PREFIX_VALUE)
                .setResultCountPerPage(1)
                .build();
        SearchResultPage searchResultPage = mAppSearchImpl.query("package1", "database1", "",
                searchSpec, /*logger=*/ null);

        // Document2 will come first because it was inserted last and default return order is
        // most recent.
        assertThat(searchResultPage.getResults()).hasSize(1);
        assertThat(searchResultPage.getResults().get(0).getGenericDocument()).isEqualTo(document2);

        long nextPageToken = searchResultPage.getNextPageToken();

        // Invalidate the token
        mAppSearchImpl.invalidateNextPageToken("package1", nextPageToken);

        // Can't get next page because we invalidated the token.
        AppSearchException e = assertThrows(AppSearchException.class,
                () -> mAppSearchImpl.getNextPage("package1", nextPageToken,
                        /*statsBuilder=*/ null));
        assertThat(e).hasMessageThat().contains(
                "Package \"package1\" cannot use nextPageToken: " + nextPageToken);
        assertThat(e.getResultCode()).isEqualTo(AppSearchResult.RESULT_SECURITY_ERROR);
    }

    @Test
    public void testInvalidateNextPageTokenWithDifferentPackage_query() throws Exception {
        // Insert package1 schema
        List<AppSearchSchema> schema1 =
                ImmutableList.of(new AppSearchSchema.Builder("schema1").build());
        mAppSearchImpl.setSchema(
                "package1",
                "database1",
                schema1,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Insert two package1 documents
        GenericDocument document1 = new GenericDocument.Builder<>("namespace", "id1",
                "schema1").build();
        GenericDocument document2 = new GenericDocument.Builder<>("namespace", "id2",
                "schema1").build();
        mAppSearchImpl.putDocument("package1", "database1", document1, /*logger=*/ null);
        mAppSearchImpl.putDocument("package1", "database1", document2, /*logger=*/ null);

        // Query for only 1 result per page
        SearchSpec searchSpec = new SearchSpec.Builder()
                .setTermMatch(TermMatchType.Code.PREFIX_VALUE)
                .setResultCountPerPage(1)
                .build();
        SearchResultPage searchResultPage = mAppSearchImpl.query("package1", "database1", "",
                searchSpec, /*logger=*/ null);

        // Document2 will come first because it was inserted last and default return order is
        // most recent.
        assertThat(searchResultPage.getResults()).hasSize(1);
        assertThat(searchResultPage.getResults().get(0).getGenericDocument()).isEqualTo(document2);

        long nextPageToken = searchResultPage.getNextPageToken();

        // Try getting next page with the wrong package, package2
        AppSearchException e = assertThrows(AppSearchException.class,
                () -> mAppSearchImpl.invalidateNextPageToken("package2",
                        nextPageToken));
        assertThat(e).hasMessageThat().contains(
                "Package \"package2\" cannot use nextPageToken: " + nextPageToken);
        assertThat(e.getResultCode()).isEqualTo(AppSearchResult.RESULT_SECURITY_ERROR);

        // Can continue getting next page for package1
        searchResultPage = mAppSearchImpl.getNextPage("package1", nextPageToken,
                /*statsBuilder=*/ null);
        assertThat(searchResultPage.getResults()).hasSize(1);
        assertThat(searchResultPage.getResults().get(0).getGenericDocument()).isEqualTo(document1);
    }

    @Test
    public void testInvalidateNextPageToken_globalQuery() throws Exception {
        // Insert package1 schema
        List<AppSearchSchema> schema1 =
                ImmutableList.of(new AppSearchSchema.Builder("schema1").build());
        mAppSearchImpl.setSchema(
                "package1",
                "database1",
                schema1,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Insert two package1 documents
        GenericDocument document1 = new GenericDocument.Builder<>("namespace", "id1",
                "schema1").build();
        GenericDocument document2 = new GenericDocument.Builder<>("namespace", "id2",
                "schema1").build();
        mAppSearchImpl.putDocument("package1", "database1", document1, /*logger=*/ null);
        mAppSearchImpl.putDocument("package1", "database1", document2, /*logger=*/ null);

        // Query for only 1 result per page
        SearchSpec searchSpec = new SearchSpec.Builder()
                .setTermMatch(TermMatchType.Code.PREFIX_VALUE)
                .setResultCountPerPage(1)
                .build();
        SearchResultPage searchResultPage = mAppSearchImpl.globalQuery(/*queryExpression=*/ "",
                searchSpec, "package1", /*visibilityStore=*/ null, Process.myUid(),
                /*callerHasSystemAccess=*/ false, /*logger=*/ null);

        // Document2 will come first because it was inserted last and default return order is
        // most recent.
        assertThat(searchResultPage.getResults()).hasSize(1);
        assertThat(searchResultPage.getResults().get(0).getGenericDocument()).isEqualTo(document2);

        long nextPageToken = searchResultPage.getNextPageToken();

        // Invalidate the token
        mAppSearchImpl.invalidateNextPageToken("package1", nextPageToken);

        // Can't get next page because we invalidated the token.
        AppSearchException e = assertThrows(AppSearchException.class,
                () -> mAppSearchImpl.getNextPage("package1", nextPageToken,
                        /*statsBuilder=*/ null));
        assertThat(e).hasMessageThat().contains(
                "Package \"package1\" cannot use nextPageToken: " + nextPageToken);
        assertThat(e.getResultCode()).isEqualTo(AppSearchResult.RESULT_SECURITY_ERROR);
    }

    @Test
    public void testInvalidateNextPageTokenWithDifferentPackage_globalQuery() throws Exception {
        // Insert package1 schema
        List<AppSearchSchema> schema1 =
                ImmutableList.of(new AppSearchSchema.Builder("schema1").build());
        mAppSearchImpl.setSchema(
                "package1",
                "database1",
                schema1,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Insert two package1 documents
        GenericDocument document1 = new GenericDocument.Builder<>("namespace", "id1",
                "schema1").build();
        GenericDocument document2 = new GenericDocument.Builder<>("namespace", "id2",
                "schema1").build();
        mAppSearchImpl.putDocument("package1", "database1", document1, /*logger=*/ null);
        mAppSearchImpl.putDocument("package1", "database1", document2, /*logger=*/ null);

        // Query for only 1 result per page
        SearchSpec searchSpec = new SearchSpec.Builder()
                .setTermMatch(TermMatchType.Code.PREFIX_VALUE)
                .setResultCountPerPage(1)
                .build();
        SearchResultPage searchResultPage = mAppSearchImpl.globalQuery(/*queryExpression=*/ "",
                searchSpec, "package1", /*visibilityStore=*/ null, Process.myUid(),
                /*callerHasSystemAccess=*/ false, /*logger=*/ null);

        // Document2 will come first because it was inserted last and default return order is
        // most recent.
        assertThat(searchResultPage.getResults()).hasSize(1);
        assertThat(searchResultPage.getResults().get(0).getGenericDocument()).isEqualTo(document2);

        long nextPageToken = searchResultPage.getNextPageToken();

        // Try getting next page with the wrong package, package2
        AppSearchException e = assertThrows(AppSearchException.class,
                () -> mAppSearchImpl.invalidateNextPageToken("package2",
                        nextPageToken));
        assertThat(e).hasMessageThat().contains(
                "Package \"package2\" cannot use nextPageToken: " + nextPageToken);
        assertThat(e.getResultCode()).isEqualTo(AppSearchResult.RESULT_SECURITY_ERROR);

        // Can continue getting next page for package1
        searchResultPage = mAppSearchImpl.getNextPage("package1", nextPageToken,
                /*statsBuilder=*/ null);
        assertThat(searchResultPage.getResults()).hasSize(1);
        assertThat(searchResultPage.getResults().get(0).getGenericDocument()).isEqualTo(document1);
    }

    @Test
    public void testRemoveEmptyDatabase_noExceptionThrown() throws Exception {
        SearchSpec searchSpec =
                new SearchSpec.Builder().addFilterSchemas("FakeType").setTermMatch(
                        TermMatchType.Code.PREFIX_VALUE).build();
        mAppSearchImpl.removeByQuery("package", "EmptyDatabase",
                "", searchSpec, /*statsBuilder=*/ null);

        searchSpec =
                new SearchSpec.Builder().addFilterNamespaces("FakeNamespace").setTermMatch(
                        TermMatchType.Code.PREFIX_VALUE).build();
        mAppSearchImpl.removeByQuery("package", "EmptyDatabase",
                "", searchSpec, /*statsBuilder=*/ null);

        searchSpec = new SearchSpec.Builder().setTermMatch(TermMatchType.Code.PREFIX_VALUE).build();
        mAppSearchImpl.removeByQuery("package", "EmptyDatabase", "", searchSpec,
                /*statsBuilder=*/ null);
    }

    @Test
    public void testSetSchema() throws Exception {
        List<SchemaTypeConfigProto> existingSchemas =
                mAppSearchImpl.getSchemaProtoLocked().getTypesList();

        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("Email").build());
        // Set schema Email to AppSearch database1
        mAppSearchImpl.setSchema(
                "package",
                "database1",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Create expected schemaType proto.
        SchemaProto expectedProto = SchemaProto.newBuilder()
                .addTypes(
                        SchemaTypeConfigProto.newBuilder()
                                .setSchemaType("package$database1/Email").setVersion(0))
                .build();

        List<SchemaTypeConfigProto> expectedTypes = new ArrayList<>();
        expectedTypes.addAll(existingSchemas);
        expectedTypes.addAll(expectedProto.getTypesList());
        assertThat(mAppSearchImpl.getSchemaProtoLocked().getTypesList())
                .containsExactlyElementsIn(expectedTypes);
    }

    @Test
    public void testSetSchema_incompatible() throws Exception {
        List<AppSearchSchema> oldSchemas = new ArrayList<>();
        oldSchemas.add(new AppSearchSchema.Builder("Email")
                .addProperty(new AppSearchSchema.StringPropertyConfig.Builder("foo")
                        .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REQUIRED)
                        .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                        .setIndexingType(
                                AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
                        .build())
                .build());
        oldSchemas.add(new AppSearchSchema.Builder("Text").build());
        // Set schema Email to AppSearch database1
        mAppSearchImpl.setSchema(
                "package",
                "database1",
                oldSchemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Create incompatible schema
        List<AppSearchSchema> newSchemas =
                Collections.singletonList(new AppSearchSchema.Builder("Email").build());

        // set email incompatible and delete text
        SetSchemaResponse setSchemaResponse = mAppSearchImpl.setSchema(
                "package",
                "database1",
                newSchemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ true,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);
        assertThat(setSchemaResponse.getDeletedTypes()).containsExactly("Text");
        assertThat(setSchemaResponse.getIncompatibleTypes()).containsExactly("Email");
    }

    @Test
    public void testRemoveSchema() throws Exception {
        List<SchemaTypeConfigProto> existingSchemas =
                mAppSearchImpl.getSchemaProtoLocked().getTypesList();

        List<AppSearchSchema> schemas = ImmutableList.of(
                new AppSearchSchema.Builder("Email").build(),
                new AppSearchSchema.Builder("Document").build());
        // Set schema Email and Document to AppSearch database1
        mAppSearchImpl.setSchema(
                "package",
                "database1",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Create expected schemaType proto.
        SchemaProto expectedProto = SchemaProto.newBuilder()
                .addTypes(
                        SchemaTypeConfigProto.newBuilder()
                                .setSchemaType("package$database1/Email").setVersion(0))
                .addTypes(SchemaTypeConfigProto.newBuilder().setSchemaType(
                        "package$database1/Document").setVersion(0))
                .build();

        // Check both schema Email and Document saved correctly.
        List<SchemaTypeConfigProto> expectedTypes = new ArrayList<>();
        expectedTypes.addAll(existingSchemas);
        expectedTypes.addAll(expectedProto.getTypesList());
        assertThat(mAppSearchImpl.getSchemaProtoLocked().getTypesList())
                .containsExactlyElementsIn(expectedTypes);

        final List<AppSearchSchema> finalSchemas = Collections.singletonList(
                new AppSearchSchema.Builder("Email").build());
        SetSchemaResponse setSchemaResponse =
                mAppSearchImpl.setSchema(
                        "package",
                        "database1",
                        finalSchemas,
                        /*visibilityStore=*/ null,
                        /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                        /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                        /*forceOverride=*/ false,
                        /*version=*/ 0,
                        /* setSchemaStatsBuilder= */ null);
        // Check the Document type has been deleted.
        assertThat(setSchemaResponse.getDeletedTypes()).containsExactly("Document");

        // ForceOverride to delete.
        mAppSearchImpl.setSchema(
                "package",
                "database1",
                finalSchemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ true,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Check Document schema is removed.
        expectedProto = SchemaProto.newBuilder()
                .addTypes(
                        SchemaTypeConfigProto.newBuilder()
                                .setSchemaType("package$database1/Email").setVersion(0))
                .build();

        expectedTypes = new ArrayList<>();
        expectedTypes.addAll(existingSchemas);
        expectedTypes.addAll(expectedProto.getTypesList());
        assertThat(mAppSearchImpl.getSchemaProtoLocked().getTypesList())
                .containsExactlyElementsIn(expectedTypes);
    }

    @Test
    public void testRemoveSchema_differentDataBase() throws Exception {
        List<SchemaTypeConfigProto> existingSchemas =
                mAppSearchImpl.getSchemaProtoLocked().getTypesList();

        // Create schemas
        List<AppSearchSchema> schemas = ImmutableList.of(
                new AppSearchSchema.Builder("Email").build(),
                new AppSearchSchema.Builder("Document").build());

        // Set schema Email and Document to AppSearch database1 and 2
        mAppSearchImpl.setSchema(
                "package",
                "database1",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);
        mAppSearchImpl.setSchema(
                "package",
                "database2",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Create expected schemaType proto.
        SchemaProto expectedProto = SchemaProto.newBuilder()
                .addTypes(
                        SchemaTypeConfigProto.newBuilder()
                                .setSchemaType("package$database1/Email").setVersion(0))
                .addTypes(SchemaTypeConfigProto.newBuilder().setSchemaType(
                        "package$database1/Document").setVersion(0))
                .addTypes(
                        SchemaTypeConfigProto.newBuilder()
                                .setSchemaType("package$database2/Email").setVersion(0))
                .addTypes(SchemaTypeConfigProto.newBuilder().setSchemaType(
                        "package$database2/Document").setVersion(0))
                .build();

        // Check Email and Document is saved in database 1 and 2 correctly.
        List<SchemaTypeConfigProto> expectedTypes = new ArrayList<>();
        expectedTypes.addAll(existingSchemas);
        expectedTypes.addAll(expectedProto.getTypesList());
        assertThat(mAppSearchImpl.getSchemaProtoLocked().getTypesList())
                .containsExactlyElementsIn(expectedTypes);

        // Save only Email to database1 this time.
        schemas = Collections.singletonList(new AppSearchSchema.Builder("Email").build());
        mAppSearchImpl.setSchema(
                "package",
                "database1",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ true,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Create expected schemaType list, database 1 should only contain Email but database 2
        // remains in same.
        expectedProto = SchemaProto.newBuilder()
                .addTypes(
                        SchemaTypeConfigProto.newBuilder()
                                .setSchemaType("package$database1/Email").setVersion(0))
                .addTypes(
                        SchemaTypeConfigProto.newBuilder()
                                .setSchemaType("package$database2/Email").setVersion(0))
                .addTypes(SchemaTypeConfigProto.newBuilder().setSchemaType(
                        "package$database2/Document").setVersion(0))
                .build();

        // Check nothing changed in database2.
        expectedTypes = new ArrayList<>();
        expectedTypes.addAll(existingSchemas);
        expectedTypes.addAll(expectedProto.getTypesList());
        assertThat(mAppSearchImpl.getSchemaProtoLocked().getTypesList())
                .containsExactlyElementsIn(expectedTypes);
    }

    @Test
    public void testClearPackageData() throws AppSearchException {
        List<SchemaTypeConfigProto> existingSchemas =
                mAppSearchImpl.getSchemaProtoLocked().getTypesList();
        Map<String, Set<String>> existingDatabases = mAppSearchImpl.getPackageToDatabases();

        // Insert package schema
        List<AppSearchSchema> schema =
                ImmutableList.of(new AppSearchSchema.Builder("schema").build());
        mAppSearchImpl.setSchema(
                "package",
                "database",
                schema,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Insert package document
        GenericDocument document = new GenericDocument.Builder<>("namespace", "id",
                "schema").build();
        mAppSearchImpl.putDocument("package", "database", document,
                /*logger=*/ null);

        // Verify the document is indexed.
        SearchSpec searchSpec =
                new SearchSpec.Builder().setTermMatch(TermMatchType.Code.PREFIX_VALUE).build();
        SearchResultPage searchResultPage = mAppSearchImpl.query("package",
                "database",  /*queryExpression=*/ "", searchSpec, /*logger=*/ null);
        assertThat(searchResultPage.getResults()).hasSize(1);
        assertThat(searchResultPage.getResults().get(0).getGenericDocument()).isEqualTo(document);

        // Remove the package
        mAppSearchImpl.clearPackageData("package");

        // Verify the document is cleared.
        searchResultPage = mAppSearchImpl.query("package2", "database2",
                /*queryExpression=*/ "", searchSpec, /*logger=*/ null);
        assertThat(searchResultPage.getResults()).isEmpty();

        // Verify the schema is cleared.
        assertThat(mAppSearchImpl.getSchemaProtoLocked().getTypesList())
                .containsExactlyElementsIn(existingSchemas);
        assertThat(mAppSearchImpl.getPackageToDatabases())
                .containsExactlyEntriesIn(existingDatabases);
    }

    @Test
    public void testPrunePackageData() throws AppSearchException {
        List<SchemaTypeConfigProto> existingSchemas =
                mAppSearchImpl.getSchemaProtoLocked().getTypesList();
        Map<String, Set<String>> existingDatabases = mAppSearchImpl.getPackageToDatabases();

        Set<String> existingPackages = new ArraySet<>(existingSchemas.size());
        for (int i = 0; i < existingSchemas.size(); i++) {
            existingPackages.add(PrefixUtil.getPackageName(existingSchemas.get(i).getSchemaType()));
        }

        // Insert schema for package A and B.
        List<AppSearchSchema> schema =
                ImmutableList.of(new AppSearchSchema.Builder("schema").build());
        mAppSearchImpl.setSchema(
                "packageA",
                "database",
                schema,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);
        mAppSearchImpl.setSchema(
                "packageB",
                "database",
                schema,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Verify these two packages is stored in AppSearch
        SchemaProto expectedProto = SchemaProto.newBuilder()
                .addTypes(
                        SchemaTypeConfigProto.newBuilder()
                                .setSchemaType("packageA$database/schema").setVersion(0))
                .addTypes(
                        SchemaTypeConfigProto.newBuilder()
                                .setSchemaType("packageB$database/schema").setVersion(0))
                .build();
        List<SchemaTypeConfigProto> expectedTypes = new ArrayList<>();
        expectedTypes.addAll(existingSchemas);
        expectedTypes.addAll(expectedProto.getTypesList());
        assertThat(mAppSearchImpl.getSchemaProtoLocked().getTypesList())
                .containsExactlyElementsIn(expectedTypes);

        // Prune packages
        mAppSearchImpl.prunePackageData(existingPackages);

        // Verify the schema is same as beginning.
        assertThat(mAppSearchImpl.getSchemaProtoLocked().getTypesList())
                .containsExactlyElementsIn(existingSchemas);
        assertThat(mAppSearchImpl.getPackageToDatabases())
                .containsExactlyEntriesIn(existingDatabases);
    }

    @Test
    public void testGetPackageToDatabases() throws Exception {
        Map<String, Set<String>> existingMapping = mAppSearchImpl.getPackageToDatabases();
        Map<String, Set<String>> expectedMapping = new ArrayMap<>();
        expectedMapping.putAll(existingMapping);

        // Has database1
        expectedMapping.put("package1", ImmutableSet.of("database1"));
        mAppSearchImpl.setSchema(
                "package1", "database1",
                Collections.singletonList(new AppSearchSchema.Builder("schema").build()),
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);
        assertThat(mAppSearchImpl.getPackageToDatabases()).containsExactlyEntriesIn(
                expectedMapping);

        // Has both databases
        expectedMapping.put("package1", ImmutableSet.of("database1", "database2"));
        mAppSearchImpl.setSchema(
                "package1", "database2",
                Collections.singletonList(new AppSearchSchema.Builder("schema").build()),
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);
        assertThat(mAppSearchImpl.getPackageToDatabases()).containsExactlyEntriesIn(
                expectedMapping);

        // Has both packages
        expectedMapping.put("package2", ImmutableSet.of("database1"));
        mAppSearchImpl.setSchema(
                "package2", "database1",
                Collections.singletonList(new AppSearchSchema.Builder("schema").build()),
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);
        assertThat(mAppSearchImpl.getPackageToDatabases()).containsExactlyEntriesIn(
                expectedMapping);
    }

    @Test
    public void testRewriteSearchResultProto() throws Exception {
        final String prefix =
                "com.package.foo" + PrefixUtil.PACKAGE_DELIMITER + "databaseName"
                        + PrefixUtil.DATABASE_DELIMITER;
        final String id = "id";
        final String namespace = prefix + "namespace";
        final String schemaType = prefix + "schema";

        // Building the SearchResult received from query.
        DocumentProto documentProto = DocumentProto.newBuilder()
                .setUri(id)
                .setNamespace(namespace)
                .setSchema(schemaType)
                .build();
        SearchResultProto.ResultProto resultProto = SearchResultProto.ResultProto.newBuilder()
                .setDocument(documentProto)
                .build();
        SearchResultProto searchResultProto = SearchResultProto.newBuilder()
                .addResults(resultProto)
                .build();
        SchemaTypeConfigProto schemaTypeConfigProto =
                SchemaTypeConfigProto.newBuilder()
                        .setSchemaType(schemaType)
                        .build();
        Map<String, Map<String, SchemaTypeConfigProto>> schemaMap = ImmutableMap.of(prefix,
                ImmutableMap.of(schemaType, schemaTypeConfigProto));

        DocumentProto.Builder strippedDocumentProto = documentProto.toBuilder();
        removePrefixesFromDocument(strippedDocumentProto);
        SearchResultPage searchResultPage =
                AppSearchImpl.rewriteSearchResultProto(searchResultProto, schemaMap);
        for (SearchResult result : searchResultPage.getResults()) {
            assertThat(result.getPackageName()).isEqualTo("com.package.foo");
            assertThat(result.getDatabaseName()).isEqualTo("databaseName");
            assertThat(result.getGenericDocument()).isEqualTo(
                    GenericDocumentToProtoConverter.toGenericDocument(
                            strippedDocumentProto.build(), prefix, schemaMap.get(prefix)));
        }
    }

    @Test
    public void testReportUsage() throws Exception {
        // Insert schema
        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("type").build());
        mAppSearchImpl.setSchema(
                "package",
                "database",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Insert two docs
        GenericDocument document1 =
                new GenericDocument.Builder<>("namespace", "id1", "type").build();
        GenericDocument document2 =
                new GenericDocument.Builder<>("namespace", "id2", "type").build();
        mAppSearchImpl.putDocument("package", "database", document1, /*logger=*/ null);
        mAppSearchImpl.putDocument("package", "database", document2, /*logger=*/ null);

        // Report some usages. id1 has 2 app and 1 system usage, id2 has 1 app and 2 system usage.
        mAppSearchImpl.reportUsage("package", "database", "namespace",
                "id1", /*usageTimestampMillis=*/ 10, /*systemUsage=*/ false);
        mAppSearchImpl.reportUsage("package", "database", "namespace",
                "id1", /*usageTimestampMillis=*/ 20, /*systemUsage=*/ false);
        mAppSearchImpl.reportUsage("package", "database", "namespace",
                "id1", /*usageTimestampMillis=*/ 1000, /*systemUsage=*/ true);

        mAppSearchImpl.reportUsage("package", "database", "namespace",
                "id2", /*usageTimestampMillis=*/ 100, /*systemUsage=*/ false);
        mAppSearchImpl.reportUsage("package", "database", "namespace",
                "id2", /*usageTimestampMillis=*/ 200, /*systemUsage=*/ true);
        mAppSearchImpl.reportUsage("package", "database", "namespace",
                "id2", /*usageTimestampMillis=*/ 150, /*systemUsage=*/ true);

        // Sort by app usage count: id1 should win
        List<SearchResult> page = mAppSearchImpl.query("package", "database", "",
                new SearchSpec.Builder()
                        .setTermMatch(SearchSpec.TERM_MATCH_EXACT_ONLY)
                        .setRankingStrategy(SearchSpec.RANKING_STRATEGY_USAGE_COUNT)
                        .build(), /*logger=*/ null).getResults();
        assertThat(page).hasSize(2);
        assertThat(page.get(0).getGenericDocument().getId()).isEqualTo("id1");
        assertThat(page.get(1).getGenericDocument().getId()).isEqualTo("id2");

        // Sort by app usage timestamp: id2 should win
        page = mAppSearchImpl.query("package", "database", "",
                new SearchSpec.Builder()
                        .setTermMatch(SearchSpec.TERM_MATCH_EXACT_ONLY)
                        .setRankingStrategy(SearchSpec.RANKING_STRATEGY_USAGE_LAST_USED_TIMESTAMP)
                        .build(), /*logger=*/ null).getResults();
        assertThat(page).hasSize(2);
        assertThat(page.get(0).getGenericDocument().getId()).isEqualTo("id2");
        assertThat(page.get(1).getGenericDocument().getId()).isEqualTo("id1");

        // Sort by system usage count: id2 should win
        page = mAppSearchImpl.query("package", "database", "",
                new SearchSpec.Builder()
                        .setTermMatch(SearchSpec.TERM_MATCH_EXACT_ONLY)
                        .setRankingStrategy(SearchSpec.RANKING_STRATEGY_SYSTEM_USAGE_COUNT)
                        .build(), /*logger=*/ null).getResults();
        assertThat(page).hasSize(2);
        assertThat(page.get(0).getGenericDocument().getId()).isEqualTo("id2");
        assertThat(page.get(1).getGenericDocument().getId()).isEqualTo("id1");

        // Sort by system usage timestamp: id1 should win
        page = mAppSearchImpl.query("package", "database", "",
                new SearchSpec.Builder()
                        .setTermMatch(SearchSpec.TERM_MATCH_EXACT_ONLY)
                        .setRankingStrategy(
                                SearchSpec.RANKING_STRATEGY_SYSTEM_USAGE_LAST_USED_TIMESTAMP)
                        .build(), /*logger=*/ null).getResults();
        assertThat(page).hasSize(2);
        assertThat(page.get(0).getGenericDocument().getId()).isEqualTo("id1");
        assertThat(page.get(1).getGenericDocument().getId()).isEqualTo("id2");
    }

    @Test
    public void testGetStorageInfoForPackage_nonexistentPackage() throws Exception {
        // "package2" doesn't exist yet, so it shouldn't have any storage size
        StorageInfo storageInfo = mAppSearchImpl.getStorageInfoForPackage("nonexistent.package");
        assertThat(storageInfo.getSizeBytes()).isEqualTo(0);
        assertThat(storageInfo.getAliveDocumentsCount()).isEqualTo(0);
        assertThat(storageInfo.getAliveNamespacesCount()).isEqualTo(0);
    }

    @Test
    public void testGetStorageInfoForPackage_withoutDocument() throws Exception {
        // Insert schema for "package1"
        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("type").build());
        mAppSearchImpl.setSchema(
                "package1",
                "database",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Since "package1" doesn't have a document, it get any space attributed to it.
        StorageInfo storageInfo = mAppSearchImpl.getStorageInfoForPackage("package1");
        assertThat(storageInfo.getSizeBytes()).isEqualTo(0);
        assertThat(storageInfo.getAliveDocumentsCount()).isEqualTo(0);
        assertThat(storageInfo.getAliveNamespacesCount()).isEqualTo(0);
    }

    @Test
    public void testGetStorageInfoForPackage_proportionalToDocuments() throws Exception {
        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("type").build());

        // Insert schema for "package1"
        mAppSearchImpl.setSchema(
                "package1",
                "database",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Insert document for "package1"
        GenericDocument document =
                new GenericDocument.Builder<>("namespace", "id1", "type").build();
        mAppSearchImpl.putDocument("package1", "database", document, /*logger=*/ null);

        // Insert schema for "package2"
        mAppSearchImpl.setSchema(
                "package2",
                "database",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Insert two documents for "package2"
        document = new GenericDocument.Builder<>("namespace", "id1", "type").build();
        mAppSearchImpl.putDocument("package2", "database", document, /*logger=*/ null);
        document = new GenericDocument.Builder<>("namespace", "id2", "type").build();
        mAppSearchImpl.putDocument("package2", "database", document, /*logger=*/ null);

        StorageInfo storageInfo = mAppSearchImpl.getStorageInfoForPackage("package1");
        long size1 = storageInfo.getSizeBytes();
        assertThat(size1).isGreaterThan(0);
        assertThat(storageInfo.getAliveDocumentsCount()).isEqualTo(1);
        assertThat(storageInfo.getAliveNamespacesCount()).isEqualTo(1);

        storageInfo = mAppSearchImpl.getStorageInfoForPackage("package2");
        long size2 = storageInfo.getSizeBytes();
        assertThat(size2).isGreaterThan(0);
        assertThat(storageInfo.getAliveDocumentsCount()).isEqualTo(2);
        assertThat(storageInfo.getAliveNamespacesCount()).isEqualTo(1);

        // Size is proportional to number of documents. Since "package2" has twice as many
        // documents as "package1", its size is twice as much too.
        assertThat(size2).isAtLeast(2 * size1);
    }

    @Test
    public void testGetStorageInfoForDatabase_nonexistentPackage() throws Exception {
        // "package2" doesn't exist yet, so it shouldn't have any storage size
        StorageInfo storageInfo = mAppSearchImpl.getStorageInfoForDatabase("nonexistent.package",
                "nonexistentDatabase");
        assertThat(storageInfo.getSizeBytes()).isEqualTo(0);
        assertThat(storageInfo.getAliveDocumentsCount()).isEqualTo(0);
        assertThat(storageInfo.getAliveNamespacesCount()).isEqualTo(0);
    }

    @Test
    public void testGetStorageInfoForDatabase_nonexistentDatabase() throws Exception {
        // Insert schema for "package1"
        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("type").build());
        mAppSearchImpl.setSchema(
                "package1",
                "database",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // "package2" doesn't exist yet, so it shouldn't have any storage size
        StorageInfo storageInfo = mAppSearchImpl.getStorageInfoForDatabase("package1",
                "nonexistentDatabase");
        assertThat(storageInfo.getSizeBytes()).isEqualTo(0);
        assertThat(storageInfo.getAliveDocumentsCount()).isEqualTo(0);
        assertThat(storageInfo.getAliveNamespacesCount()).isEqualTo(0);
    }

    @Test
    public void testGetStorageInfoForDatabase_withoutDocument() throws Exception {
        // Insert schema for "package1"
        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("type").build());
        mAppSearchImpl.setSchema(
                "package1",
                "database1",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Since "package1", "database1" doesn't have a document, it get any space attributed to it.
        StorageInfo storageInfo = mAppSearchImpl.getStorageInfoForDatabase("package1", "database1");
        assertThat(storageInfo.getSizeBytes()).isEqualTo(0);
        assertThat(storageInfo.getAliveDocumentsCount()).isEqualTo(0);
        assertThat(storageInfo.getAliveNamespacesCount()).isEqualTo(0);
    }

    @Test
    public void testGetStorageInfoForDatabase_proportionalToDocuments() throws Exception {
        // Insert schema for "package1", "database1" and "database2"
        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("type").build());
        mAppSearchImpl.setSchema(
                "package1",
                "database1",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);
        mAppSearchImpl.setSchema(
                "package1",
                "database2",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Add a document for "package1", "database1"
        GenericDocument document =
                new GenericDocument.Builder<>("namespace1", "id1", "type").build();
        mAppSearchImpl.putDocument("package1", "database1", document, /*logger=*/ null);

        // Add two documents for "package1", "database2"
        document = new GenericDocument.Builder<>("namespace1", "id1", "type").build();
        mAppSearchImpl.putDocument("package1", "database2", document, /*logger=*/ null);
        document = new GenericDocument.Builder<>("namespace1", "id2", "type").build();
        mAppSearchImpl.putDocument("package1", "database2", document, /*logger=*/ null);


        StorageInfo storageInfo = mAppSearchImpl.getStorageInfoForDatabase("package1", "database1");
        long size1 = storageInfo.getSizeBytes();
        assertThat(size1).isGreaterThan(0);
        assertThat(storageInfo.getAliveDocumentsCount()).isEqualTo(1);
        assertThat(storageInfo.getAliveNamespacesCount()).isEqualTo(1);

        storageInfo = mAppSearchImpl.getStorageInfoForDatabase("package1", "database2");
        long size2 = storageInfo.getSizeBytes();
        assertThat(size2).isGreaterThan(0);
        assertThat(storageInfo.getAliveDocumentsCount()).isEqualTo(2);
        assertThat(storageInfo.getAliveNamespacesCount()).isEqualTo(1);

        // Size is proportional to number of documents. Since "database2" has twice as many
        // documents as "database1", its size is twice as much too.
        assertThat(size2).isAtLeast(2 * size1);
    }

    @Test
    public void testThrowsExceptionIfClosed() throws Exception {
        // Initial check that we could do something at first.
        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("type").build());
        mAppSearchImpl.setSchema(
                "package",
                "database",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        mAppSearchImpl.close();

        // Check all our public APIs
        assertThrows(IllegalStateException.class, () -> mAppSearchImpl.setSchema(
                "package",
                "database",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null));

        assertThrows(IllegalStateException.class, () -> mAppSearchImpl.getSchema(
                "package", "database"));

        assertThrows(IllegalStateException.class, () -> mAppSearchImpl.putDocument(
                "package",
                "database",
                new GenericDocument.Builder<>("namespace", "id", "type").build(),
                /*logger=*/ null));

        assertThrows(IllegalStateException.class, () -> mAppSearchImpl.getDocument(
                "package", "database", "namespace", "id", Collections.emptyMap()));

        assertThrows(IllegalStateException.class, () -> mAppSearchImpl.query(
                "package",
                "database",
                "query",
                new SearchSpec.Builder().build(),
                /*logger=*/ null));

        assertThrows(IllegalStateException.class, () -> mAppSearchImpl.globalQuery(
                "query",
                new SearchSpec.Builder().build(),
                "package",
                /*visibilityStore=*/ null,
                Process.INVALID_UID,
                /*callerHasSystemAccess=*/ false,
                /*logger=*/ null));

        assertThrows(IllegalStateException.class, () -> mAppSearchImpl.getNextPage("package",
                /*nextPageToken=*/ 1L, /*statsBuilder=*/ null));

        assertThrows(IllegalStateException.class, () -> mAppSearchImpl.invalidateNextPageToken(
                "package",
                /*nextPageToken=*/ 1L));

        assertThrows(IllegalStateException.class, () -> mAppSearchImpl.reportUsage(
                "package", "database", "namespace", "id",
                /*usageTimestampMillis=*/ 1000L, /*systemUsage=*/ false));

        assertThrows(IllegalStateException.class, () -> mAppSearchImpl.remove(
                "package", "database", "namespace", "id", /*removeStatsBuilder=*/ null));

        assertThrows(IllegalStateException.class, () -> mAppSearchImpl.removeByQuery(
                "package",
                "database",
                "query",
                new SearchSpec.Builder().build(),
                /*removeStatsBuilder=*/ null));

        assertThrows(IllegalStateException.class, () -> mAppSearchImpl.getStorageInfoForPackage(
                "package"));

        assertThrows(IllegalStateException.class, () -> mAppSearchImpl.getStorageInfoForDatabase(
                "package", "database"));

        assertThrows(IllegalStateException.class, () -> mAppSearchImpl.persistToDisk(
                PersistType.Code.FULL));
    }

    @Test
    public void testPutPersistsWithLiteFlush() throws Exception {
        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("type").build());
        mAppSearchImpl.setSchema(
                "package",
                "database",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Add a document and persist it.
        GenericDocument document =
                new GenericDocument.Builder<>("namespace1", "id1", "type").build();
        mAppSearchImpl.putDocument("package", "database", document, /*logger=*/null);
        mAppSearchImpl.persistToDisk(PersistType.Code.LITE);

        GenericDocument getResult = mAppSearchImpl.getDocument("package", "database", "namespace1",
                "id1",
                Collections.emptyMap());
        assertThat(getResult).isEqualTo(document);

        // That document should be visible even from another instance.
        AppSearchImpl appSearchImpl2 = AppSearchImpl.create(
                mAppSearchDir,
                new UnlimitedLimitConfig(),
                /*initStatsBuilder=*/ null,
                ALWAYS_OPTIMIZE);
        getResult = appSearchImpl2.getDocument("package", "database", "namespace1",
                "id1",
                Collections.emptyMap());
        assertThat(getResult).isEqualTo(document);
        appSearchImpl2.close();
    }

    @Test
    public void testDeletePersistsWithLiteFlush() throws Exception {
        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("type").build());
        mAppSearchImpl.setSchema(
                "package",
                "database",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Add two documents and persist them.
        GenericDocument document1 =
                new GenericDocument.Builder<>("namespace1", "id1", "type").build();
        mAppSearchImpl.putDocument("package", "database", document1, /*logger=*/null);
        GenericDocument document2 =
                new GenericDocument.Builder<>("namespace1", "id2", "type").build();
        mAppSearchImpl.putDocument("package", "database", document2, /*logger=*/null);
        mAppSearchImpl.persistToDisk(PersistType.Code.LITE);

        GenericDocument getResult = mAppSearchImpl.getDocument("package", "database", "namespace1",
                "id1",
                Collections.emptyMap());
        assertThat(getResult).isEqualTo(document1);
        getResult = mAppSearchImpl.getDocument("package", "database", "namespace1",
                "id2",
                Collections.emptyMap());
        assertThat(getResult).isEqualTo(document2);

        // Delete the first document
        mAppSearchImpl.remove("package", "database", "namespace1", "id1", /*statsBuilder=*/ null);
        mAppSearchImpl.persistToDisk(PersistType.Code.LITE);
        assertThrows(AppSearchException.class, () -> mAppSearchImpl.getDocument("package",
                "database",
                "namespace1",
                "id1",
                Collections.emptyMap()));
        getResult = mAppSearchImpl.getDocument("package", "database", "namespace1",
                "id2",
                Collections.emptyMap());
        assertThat(getResult).isEqualTo(document2);

        // Only the second document should be retrievable from another instance.
        AppSearchImpl appSearchImpl2 = AppSearchImpl.create(
                mAppSearchDir,
                new UnlimitedLimitConfig(),
                /*initStatsBuilder=*/ null,
                ALWAYS_OPTIMIZE);
        assertThrows(AppSearchException.class, () -> appSearchImpl2.getDocument("package",
                "database",
                "namespace1",
                "id1",
                Collections.emptyMap()));
        getResult = appSearchImpl2.getDocument("package", "database", "namespace1",
                "id2",
                Collections.emptyMap());
        assertThat(getResult).isEqualTo(document2);
        appSearchImpl2.close();
    }

    @Test
    public void testDeleteByQueryPersistsWithLiteFlush() throws Exception {
        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("type").build());
        mAppSearchImpl.setSchema(
                "package",
                "database",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Add two documents and persist them.
        GenericDocument document1 =
                new GenericDocument.Builder<>("namespace1", "id1", "type").build();
        mAppSearchImpl.putDocument("package", "database", document1, /*logger=*/null);
        GenericDocument document2 =
                new GenericDocument.Builder<>("namespace2", "id2", "type").build();
        mAppSearchImpl.putDocument("package", "database", document2, /*logger=*/null);
        mAppSearchImpl.persistToDisk(PersistType.Code.LITE);

        GenericDocument getResult = mAppSearchImpl.getDocument("package", "database", "namespace1",
                "id1",
                Collections.emptyMap());
        assertThat(getResult).isEqualTo(document1);
        getResult = mAppSearchImpl.getDocument("package", "database", "namespace2",
                "id2",
                Collections.emptyMap());
        assertThat(getResult).isEqualTo(document2);

        // Delete the first document
        mAppSearchImpl.removeByQuery("package", "database", "",
                new SearchSpec.Builder().addFilterNamespaces("namespace1").setTermMatch(
                        SearchSpec.TERM_MATCH_EXACT_ONLY).build(), /*statsBuilder=*/ null);
        mAppSearchImpl.persistToDisk(PersistType.Code.LITE);
        assertThrows(AppSearchException.class, () -> mAppSearchImpl.getDocument("package",
                "database",
                "namespace1",
                "id1",
                Collections.emptyMap()));
        getResult = mAppSearchImpl.getDocument("package", "database", "namespace2",
                "id2",
                Collections.emptyMap());
        assertThat(getResult).isEqualTo(document2);

        // Only the second document should be retrievable from another instance.
        AppSearchImpl appSearchImpl2 = AppSearchImpl.create(
                mAppSearchDir,
                new UnlimitedLimitConfig(),
                /*initStatsBuilder=*/ null,
                ALWAYS_OPTIMIZE);
        assertThrows(AppSearchException.class, () -> appSearchImpl2.getDocument("package",
                "database",
                "namespace1",
                "id1",
                Collections.emptyMap()));
        getResult = appSearchImpl2.getDocument("package", "database", "namespace2",
                "id2",
                Collections.emptyMap());
        assertThat(getResult).isEqualTo(document2);
        appSearchImpl2.close();
    }

    @Test
    public void testGetIcingSearchEngineStorageInfo() throws Exception {
        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("type").build());
        mAppSearchImpl.setSchema(
                "package",
                "database",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Add two documents
        GenericDocument document1 =
                new GenericDocument.Builder<>("namespace1", "id1", "type").build();
        mAppSearchImpl.putDocument("package", "database", document1, /*logger=*/null);
        GenericDocument document2 =
                new GenericDocument.Builder<>("namespace1", "id2", "type").build();
        mAppSearchImpl.putDocument("package", "database", document2, /*logger=*/null);

        StorageInfoProto storageInfo = mAppSearchImpl.getRawStorageInfoProto();

        // Simple checks to verify if we can get correct StorageInfoProto from IcingSearchEngine
        // No need to cover all the fields
        assertThat(storageInfo.getTotalStorageSize()).isGreaterThan(0);
        assertThat(
                storageInfo.getDocumentStorageInfo().getNumAliveDocuments())
                .isEqualTo(2);
        assertThat(
                storageInfo.getSchemaStoreStorageInfo().getNumSchemaTypes())
                .isEqualTo(1);
    }

    @Test
    public void testLimitConfig_DocumentSize() throws Exception {
        // Create a new mAppSearchImpl with a lower limit
        mAppSearchImpl.close();
        mAppSearchImpl = AppSearchImpl.create(
                mTemporaryFolder.newFolder(),
                new LimitConfig() {
                    @Override
                    public int getMaxDocumentSizeBytes() {
                        return 80;
                    }

                    @Override
                    public int getMaxDocumentCount() {
                        return 1;
                    }
                },
                /*initStatsBuilder=*/ null, ALWAYS_OPTIMIZE);

        // Insert schema
        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("type").build());
        mAppSearchImpl.setSchema(
                "package",
                "database",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Insert a document which is too large
        GenericDocument document = new GenericDocument.Builder<>(
                "this_namespace_is_long_to_make_the_doc_big", "id", "type").build();
        AppSearchException e = assertThrows(AppSearchException.class, () ->
                mAppSearchImpl.putDocument("package", "database", document, /*logger=*/ null));
        assertThat(e.getResultCode()).isEqualTo(AppSearchResult.RESULT_OUT_OF_SPACE);
        assertThat(e).hasMessageThat().contains(
                "Document \"id\" for package \"package\" serialized to 99 bytes, which exceeds"
                        + " limit of 80 bytes");

        // Make sure this failure didn't increase our document count. We should still be able to
        // index 1 document.
        GenericDocument document2 =
                new GenericDocument.Builder<>("namespace", "id2", "type").build();
        mAppSearchImpl.putDocument("package", "database", document2, /*logger=*/ null);

        // Now we should get a failure
        GenericDocument document3 =
                new GenericDocument.Builder<>("namespace", "id3", "type").build();
        e = assertThrows(AppSearchException.class, () ->
                mAppSearchImpl.putDocument("package", "database", document3, /*logger=*/ null));
        assertThat(e.getResultCode()).isEqualTo(AppSearchResult.RESULT_OUT_OF_SPACE);
        assertThat(e).hasMessageThat().contains(
                "Package \"package\" exceeded limit of 1 documents");
    }

    @Test
    public void testLimitConfig_Init() throws Exception {
        // Create a new mAppSearchImpl with a lower limit
        mAppSearchImpl.close();
        File tempFolder = mTemporaryFolder.newFolder();
        mAppSearchImpl = AppSearchImpl.create(
                tempFolder,
                new LimitConfig() {
                    @Override
                    public int getMaxDocumentSizeBytes() {
                        return 80;
                    }

                    @Override
                    public int getMaxDocumentCount() {
                        return 1;
                    }
                },
                /*initStatsBuilder=*/ null, ALWAYS_OPTIMIZE);

        // Insert schema
        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("type").build());
        mAppSearchImpl.setSchema(
                "package",
                "database",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Index a document
        mAppSearchImpl.putDocument(
                "package",
                "database",
                new GenericDocument.Builder<>("namespace", "id1", "type").build(),
                /*logger=*/ null);

        // Now we should get a failure
        GenericDocument document2 =
                new GenericDocument.Builder<>("namespace", "id2", "type").build();
        AppSearchException e = assertThrows(AppSearchException.class, () ->
                mAppSearchImpl.putDocument("package", "database", document2, /*logger=*/ null));
        assertThat(e.getResultCode()).isEqualTo(AppSearchResult.RESULT_OUT_OF_SPACE);
        assertThat(e).hasMessageThat().contains(
                "Package \"package\" exceeded limit of 1 documents");

        // Close and reinitialize AppSearchImpl
        mAppSearchImpl.close();
        mAppSearchImpl = AppSearchImpl.create(
                tempFolder,
                new LimitConfig() {
                    @Override
                    public int getMaxDocumentSizeBytes() {
                        return 80;
                    }

                    @Override
                    public int getMaxDocumentCount() {
                        return 1;
                    }
                },
                /*initStatsBuilder=*/ null, ALWAYS_OPTIMIZE);

        // Make sure the limit is maintained
        e = assertThrows(AppSearchException.class, () ->
                mAppSearchImpl.putDocument("package", "database", document2, /*logger=*/ null));
        assertThat(e.getResultCode()).isEqualTo(AppSearchResult.RESULT_OUT_OF_SPACE);
        assertThat(e).hasMessageThat().contains(
                "Package \"package\" exceeded limit of 1 documents");
    }

    @Test
    public void testLimitConfig_Remove() throws Exception {
        // Create a new mAppSearchImpl with a lower limit
        mAppSearchImpl.close();
        mAppSearchImpl = AppSearchImpl.create(
                mTemporaryFolder.newFolder(),
                new LimitConfig() {
                    @Override
                    public int getMaxDocumentSizeBytes() {
                        return Integer.MAX_VALUE;
                    }

                    @Override
                    public int getMaxDocumentCount() {
                        return 3;
                    }
                },
                /*initStatsBuilder=*/ null, ALWAYS_OPTIMIZE);

        // Insert schema
        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("type").build());
        mAppSearchImpl.setSchema(
                "package",
                "database",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Index 3 documents
        mAppSearchImpl.putDocument(
                "package",
                "database",
                new GenericDocument.Builder<>("namespace", "id1", "type").build(),
                /*logger=*/ null);
        mAppSearchImpl.putDocument(
                "package",
                "database",
                new GenericDocument.Builder<>("namespace", "id2", "type").build(),
                /*logger=*/ null);
        mAppSearchImpl.putDocument(
                "package",
                "database",
                new GenericDocument.Builder<>("namespace", "id3", "type").build(),
                /*logger=*/ null);

        // Now we should get a failure
        GenericDocument document4 =
                new GenericDocument.Builder<>("namespace", "id4", "type").build();
        AppSearchException e = assertThrows(AppSearchException.class, () ->
                mAppSearchImpl.putDocument("package", "database", document4, /*logger=*/ null));
        assertThat(e.getResultCode()).isEqualTo(AppSearchResult.RESULT_OUT_OF_SPACE);
        assertThat(e).hasMessageThat().contains(
                "Package \"package\" exceeded limit of 3 documents");

        // Remove a document that doesn't exist
        assertThrows(AppSearchException.class, () ->
                mAppSearchImpl.remove(
                        "package", "database", "namespace", "id4", /*removeStatsBuilder=*/null));

        // Should still fail
        e = assertThrows(AppSearchException.class, () ->
                mAppSearchImpl.putDocument("package", "database", document4, /*logger=*/ null));
        assertThat(e.getResultCode()).isEqualTo(AppSearchResult.RESULT_OUT_OF_SPACE);
        assertThat(e).hasMessageThat().contains(
                "Package \"package\" exceeded limit of 3 documents");

        // Remove a document that does exist
        mAppSearchImpl.remove(
                "package", "database", "namespace", "id2", /*removeStatsBuilder=*/null);

        // Now doc4 should work
        mAppSearchImpl.putDocument("package", "database", document4, /*logger=*/ null);

        // The next one should fail again
        e = assertThrows(AppSearchException.class, () -> mAppSearchImpl.putDocument(
                "package",
                "database",
                new GenericDocument.Builder<>("namespace", "id5", "type").build(),
                /*logger=*/ null));
        assertThat(e.getResultCode()).isEqualTo(AppSearchResult.RESULT_OUT_OF_SPACE);
        assertThat(e).hasMessageThat().contains(
                "Package \"package\" exceeded limit of 3 documents");
    }

    @Test
    public void testLimitConfig_DifferentPackages() throws Exception {
        // Create a new mAppSearchImpl with a lower limit
        mAppSearchImpl.close();
        File tempFolder = mTemporaryFolder.newFolder();
        mAppSearchImpl = AppSearchImpl.create(
                tempFolder,
                new LimitConfig() {
                    @Override
                    public int getMaxDocumentSizeBytes() {
                        return Integer.MAX_VALUE;
                    }

                    @Override
                    public int getMaxDocumentCount() {
                        return 2;
                    }
                },
                /*initStatsBuilder=*/ null, ALWAYS_OPTIMIZE);

        // Insert schema
        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("type").build());
        mAppSearchImpl.setSchema(
                "package1",
                "database1",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);
        mAppSearchImpl.setSchema(
                "package1",
                "database2",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);
        mAppSearchImpl.setSchema(
                "package2",
                "database1",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);
        mAppSearchImpl.setSchema(
                "package2",
                "database2",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Index documents in package1/database1
        mAppSearchImpl.putDocument(
                "package1",
                "database1",
                new GenericDocument.Builder<>("namespace", "id1", "type").build(),
                /*logger=*/ null);
        mAppSearchImpl.putDocument(
                "package1",
                "database2",
                new GenericDocument.Builder<>("namespace", "id2", "type").build(),
                /*logger=*/ null);

        // Indexing a third doc into package1 should fail (here we use database3)
        AppSearchException e = assertThrows(AppSearchException.class, () ->
                mAppSearchImpl.putDocument(
                        "package1",
                        "database3",
                        new GenericDocument.Builder<>("namespace", "id3", "type").build(),
                        /*logger=*/ null));
        assertThat(e.getResultCode()).isEqualTo(AppSearchResult.RESULT_OUT_OF_SPACE);
        assertThat(e).hasMessageThat().contains(
                "Package \"package1\" exceeded limit of 2 documents");

        // Indexing a doc into package2 should succeed
        mAppSearchImpl.putDocument(
                "package2",
                "database1",
                new GenericDocument.Builder<>("namespace", "id1", "type").build(),
                /*logger=*/ null);

        // Reinitialize to make sure packages are parsed correctly on init
        mAppSearchImpl.close();
        mAppSearchImpl = AppSearchImpl.create(
                tempFolder,
                new LimitConfig() {
                    @Override
                    public int getMaxDocumentSizeBytes() {
                        return Integer.MAX_VALUE;
                    }

                    @Override
                    public int getMaxDocumentCount() {
                        return 2;
                    }
                },
                /*initStatsBuilder=*/ null, ALWAYS_OPTIMIZE);

        // package1 should still be out of space
        e = assertThrows(AppSearchException.class, () ->
                mAppSearchImpl.putDocument(
                        "package1",
                        "database4",
                        new GenericDocument.Builder<>("namespace", "id4", "type").build(),
                        /*logger=*/ null));
        assertThat(e.getResultCode()).isEqualTo(AppSearchResult.RESULT_OUT_OF_SPACE);
        assertThat(e).hasMessageThat().contains(
                "Package \"package1\" exceeded limit of 2 documents");

        // package2 has room for one more
        mAppSearchImpl.putDocument(
                "package2",
                "database2",
                new GenericDocument.Builder<>("namespace", "id2", "type").build(),
                /*logger=*/ null);

        // now package2 really is out of space
        e = assertThrows(AppSearchException.class, () ->
                mAppSearchImpl.putDocument(
                        "package2",
                        "database3",
                        new GenericDocument.Builder<>("namespace", "id3", "type").build(),
                        /*logger=*/ null));
        assertThat(e.getResultCode()).isEqualTo(AppSearchResult.RESULT_OUT_OF_SPACE);
        assertThat(e).hasMessageThat().contains(
                "Package \"package2\" exceeded limit of 2 documents");
    }

    @Test
    public void testLimitConfig_RemoveByQuery() throws Exception {
        // Create a new mAppSearchImpl with a lower limit
        mAppSearchImpl.close();
        mAppSearchImpl = AppSearchImpl.create(
                mTemporaryFolder.newFolder(),
                new LimitConfig() {
                    @Override
                    public int getMaxDocumentSizeBytes() {
                        return Integer.MAX_VALUE;
                    }

                    @Override
                    public int getMaxDocumentCount() {
                        return 3;
                    }
                },
                /*initStatsBuilder=*/ null, ALWAYS_OPTIMIZE);

        // Insert schema
        List<AppSearchSchema> schemas = Collections.singletonList(
                new AppSearchSchema.Builder("type")
                        .addProperty(new AppSearchSchema.StringPropertyConfig.Builder("body")
                                .setIndexingType(
                                        AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
                                .setTokenizerType(
                                        AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                                .build())
                        .build());
        mAppSearchImpl.setSchema(
                "package",
                "database",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Index 3 documents
        mAppSearchImpl.putDocument(
                "package",
                "database",
                new GenericDocument.Builder<>("namespace", "id1", "type")
                        .setPropertyString("body", "tablet")
                        .build(),
                /*logger=*/ null);
        mAppSearchImpl.putDocument(
                "package",
                "database",
                new GenericDocument.Builder<>("namespace", "id2", "type")
                        .setPropertyString("body", "tabby")
                        .build(),
                /*logger=*/ null);
        mAppSearchImpl.putDocument(
                "package",
                "database",
                new GenericDocument.Builder<>("namespace", "id3", "type")
                        .setPropertyString("body", "grabby")
                        .build(),
                /*logger=*/ null);

        // Now we should get a failure
        GenericDocument document4 =
                new GenericDocument.Builder<>("namespace", "id4", "type").build();
        AppSearchException e = assertThrows(AppSearchException.class, () ->
                mAppSearchImpl.putDocument("package", "database", document4, /*logger=*/ null));
        assertThat(e.getResultCode()).isEqualTo(AppSearchResult.RESULT_OUT_OF_SPACE);
        assertThat(e).hasMessageThat().contains(
                "Package \"package\" exceeded limit of 3 documents");

        // Run removebyquery, deleting nothing
        mAppSearchImpl.removeByQuery(
                "package",
                "database",
                "nothing",
                new SearchSpec.Builder().build(),
                /*removeStatsBuilder=*/null);

        // Should still fail
        e = assertThrows(AppSearchException.class, () ->
                mAppSearchImpl.putDocument("package", "database", document4, /*logger=*/ null));
        assertThat(e.getResultCode()).isEqualTo(AppSearchResult.RESULT_OUT_OF_SPACE);
        assertThat(e).hasMessageThat().contains(
                "Package \"package\" exceeded limit of 3 documents");

        // Remove "tab*"
        mAppSearchImpl.removeByQuery(
                "package",
                "database",
                "tab",
                new SearchSpec.Builder().build(),
                /*removeStatsBuilder=*/null);

        // Now doc4 and doc5 should work
        mAppSearchImpl.putDocument("package", "database", document4, /*logger=*/ null);
        mAppSearchImpl.putDocument(
                "package",
                "database",
                new GenericDocument.Builder<>("namespace", "id5", "type").build(),
                /*logger=*/ null);

        // We only deleted 2 docs so the next one should fail again
        e = assertThrows(AppSearchException.class, () -> mAppSearchImpl.putDocument(
                "package",
                "database",
                new GenericDocument.Builder<>("namespace", "id6", "type").build(),
                /*logger=*/ null));
        assertThat(e.getResultCode()).isEqualTo(AppSearchResult.RESULT_OUT_OF_SPACE);
        assertThat(e).hasMessageThat().contains(
                "Package \"package\" exceeded limit of 3 documents");
    }

    @Test
    public void testLimitConfig_Replace() throws Exception {
        // Create a new mAppSearchImpl with a lower limit
        mAppSearchImpl.close();
        mAppSearchImpl = AppSearchImpl.create(
                mTemporaryFolder.newFolder(),
                new LimitConfig() {
                    @Override
                    public int getMaxDocumentSizeBytes() {
                        return Integer.MAX_VALUE;
                    }

                    @Override
                    public int getMaxDocumentCount() {
                        return 2;
                    }
                },
                /*initStatsBuilder=*/ null, ALWAYS_OPTIMIZE);

        // Insert schema
        List<AppSearchSchema> schemas = Collections.singletonList(
                new AppSearchSchema.Builder("type")
                        .addProperty(
                                new AppSearchSchema.StringPropertyConfig.Builder("body").build())
                        .build());
        mAppSearchImpl.setSchema(
                "package",
                "database",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Index a document
        mAppSearchImpl.putDocument(
                "package",
                "database",
                new GenericDocument.Builder<>("namespace", "id1", "type")
                        .setPropertyString("body", "id1.orig")
                        .build(),
                /*logger=*/ null);
        // Replace it with another doc
        mAppSearchImpl.putDocument(
                "package",
                "database",
                new GenericDocument.Builder<>("namespace", "id1", "type")
                        .setPropertyString("body", "id1.new")
                        .build(),
                /*logger=*/ null);

        // Index id2. This should pass but only because we check for replacements.
        mAppSearchImpl.putDocument(
                "package",
                "database",
                new GenericDocument.Builder<>("namespace", "id2", "type").build(),
                /*logger=*/ null);

        // Now we should get a failure on id3
        GenericDocument document3 =
                new GenericDocument.Builder<>("namespace", "id3", "type").build();
        AppSearchException e = assertThrows(AppSearchException.class, () ->
                mAppSearchImpl.putDocument("package", "database", document3, /*logger=*/ null));
        assertThat(e.getResultCode()).isEqualTo(AppSearchResult.RESULT_OUT_OF_SPACE);
        assertThat(e).hasMessageThat().contains(
                "Package \"package\" exceeded limit of 2 documents");
    }

    @Test
    public void testLimitConfig_ReplaceReinit() throws Exception {
        // Create a new mAppSearchImpl with a lower limit
        mAppSearchImpl.close();
        File tempFolder = mTemporaryFolder.newFolder();
        mAppSearchImpl = AppSearchImpl.create(
                tempFolder,
                new LimitConfig() {
                    @Override
                    public int getMaxDocumentSizeBytes() {
                        return Integer.MAX_VALUE;
                    }

                    @Override
                    public int getMaxDocumentCount() {
                        return 2;
                    }
                },
                /*initStatsBuilder=*/ null, ALWAYS_OPTIMIZE);

        // Insert schema
        List<AppSearchSchema> schemas = Collections.singletonList(
                new AppSearchSchema.Builder("type")
                        .addProperty(
                                new AppSearchSchema.StringPropertyConfig.Builder("body").build())
                        .build());
        mAppSearchImpl.setSchema(
                "package",
                "database",
                schemas,
                /*visibilityStore=*/ null,
                /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0,
                /* setSchemaStatsBuilder= */ null);

        // Index a document
        mAppSearchImpl.putDocument(
                "package",
                "database",
                new GenericDocument.Builder<>("namespace", "id1", "type")
                        .setPropertyString("body", "id1.orig")
                        .build(),
                /*logger=*/ null);
        // Replace it with another doc
        mAppSearchImpl.putDocument(
                "package",
                "database",
                new GenericDocument.Builder<>("namespace", "id1", "type")
                        .setPropertyString("body", "id1.new")
                        .build(),
                /*logger=*/ null);

        // Reinitialize to make sure replacements are correctly accounted for by init
        mAppSearchImpl.close();
        mAppSearchImpl = AppSearchImpl.create(
                tempFolder,
                new LimitConfig() {
                    @Override
                    public int getMaxDocumentSizeBytes() {
                        return Integer.MAX_VALUE;
                    }

                    @Override
                    public int getMaxDocumentCount() {
                        return 2;
                    }
                },
                /*initStatsBuilder=*/ null, ALWAYS_OPTIMIZE);

        // Index id2. This should pass but only because we check for replacements.
        mAppSearchImpl.putDocument(
                "package",
                "database",
                new GenericDocument.Builder<>("namespace", "id2", "type").build(),
                /*logger=*/ null);

        // Now we should get a failure on id3
        GenericDocument document3 =
                new GenericDocument.Builder<>("namespace", "id3", "type").build();
        AppSearchException e = assertThrows(AppSearchException.class, () ->
                mAppSearchImpl.putDocument("package", "database", document3, /*logger=*/ null));
        assertThat(e.getResultCode()).isEqualTo(AppSearchResult.RESULT_OUT_OF_SPACE);
        assertThat(e).hasMessageThat().contains(
                "Package \"package\" exceeded limit of 2 documents");
    }
}
