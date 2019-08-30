package com.linkedin.avro2tf.jobs

import java.io.File

import com.databricks.spark.avro._
import com.linkedin.avro2tf.helpers.TensorizeInJobHelper
import com.linkedin.avro2tf.parsers.TensorizeInJobParamsParser
import com.linkedin.avro2tf.utils.ConstantsForTest._
import com.linkedin.avro2tf.utils.{CommonUtils, TestUtil, WithLocalSparkSession}

import org.apache.commons.io.FileUtils
import org.apache.spark.sql.types._
import org.apache.spark.sql.Row
import org.testng.Assert._
import org.testng.annotations.{DataProvider, Test}

class FeatureIndicesConversionTest extends WithLocalSparkSession {

  /**
   * Data provider for feature indices conversion test
   *
   */
  @DataProvider
  def testData(): Array[Array[Any]] = {

    Array(
      Array(AVRO_RECORD),
      Array(TF_RECORD)
    )
  }

  /**
   * Test the correctness of indices conversion job
   */
  @Test(dataProvider = "testData")
  def testConversion(outputFormat: String): Unit = {

    val tensorizeInConfig = new File(
      getClass.getClassLoader.getResource(TENSORIZEIN_CONFIG_PATH_VALUE_SAMPLE).getFile
    ).getAbsolutePath
    FileUtils.deleteDirectory(new File(WORKING_DIRECTORY_INDICES_CONVERSION))

    val params = Seq(
      INPUT_PATHS_NAME, INPUT_TEXT_FILE_PATHS,
      WORKING_DIRECTORY_NAME, WORKING_DIRECTORY_INDICES_CONVERSION,
      TENSORIZEIN_CONFIG_PATH_NAME, tensorizeInConfig,
      OUTPUT_FORMAT_NAME, outputFormat
    )
    val dataFrame = session.read.avro(INPUT_TEXT_FILE_PATHS)
    val tensorizeInParams = if (outputFormat == TF_RECORD) {
      TensorizeInJobParamsParser.parse(params)
    } else {
      TensorizeInJobParamsParser.parse(params ++ Seq(EXTRA_COLUMNS_TO_KEEP_NAME, EXTRA_COLUMNS_TO_KEEP_VALUE))
    }

    val dataFrameExtracted = FeatureExtraction.run(dataFrame, tensorizeInParams)
    val dataFrameTransformed = FeatureTransformation.run(dataFrameExtracted, tensorizeInParams)
    FeatureListGeneration.run(dataFrameTransformed, tensorizeInParams)
    val convertedDataFrame = FeatureIndicesConversion.run(dataFrameTransformed, tensorizeInParams)

    TestUtil.checkOutputColumns(convertedDataFrame, tensorizeInParams)

    // check if the type of "wordSeq" column is the expected Seq[Long]
    val convertedTextColummType = convertedDataFrame.schema(FEATURE_WORD_SEQ_COL_NAME).dataType
    assertTrue(CommonUtils.isArrayOfLong(convertedTextColummType))
    assertTrue(convertedDataFrame.schema(FEATURE_FIRST_WORD_COL_NAME).dataType.isInstanceOf[LongType])

    // check if the type of "words_wideFeatures_sparse" column is the expected SparseVector type
    val convertedNTVSparseColummType = convertedDataFrame.schema(FEATURE_WORDS_WIDE_FEATURES_SPARSE_COL_NAME).dataType
    assertTrue(convertedNTVSparseColummType.isInstanceOf[StructType])
    val convertedNTVStructType = convertedNTVSparseColummType.asInstanceOf[StructType]
    assertTrue(CommonUtils.isSparseVector(convertedNTVStructType))
    assertTrue(CommonUtils.isArrayOfLong(convertedNTVStructType(SPARSE_VECTOR_INDICES_FIELD_NAME).dataType))
    assertTrue(CommonUtils.isArrayOfFloat(convertedNTVStructType(SPARSE_VECTOR_VALUES_FIELD_NAME).dataType))

    // check if the type of "words_wideFeatures_dense" column is the expected dense vector type
    val convertedNTVDenseColummType = convertedDataFrame.schema(FEATURE_WORDS_WIDE_FEATURES_DENSE_COL_NAME).dataType
    assertTrue(CommonUtils.isArrayOfFloat(convertedNTVDenseColummType))

    // Make sure the dense and sparse format have the same value at the same index
    convertedDataFrame.foreach {
      row => {
        val sparseVector = row.getAs[Row](FEATURE_WORDS_WIDE_FEATURES_SPARSE_COL_NAME)
        val indices = sparseVector.getAs[Seq[Long]](SPARSE_VECTOR_INDICES_FIELD_NAME)
        val values = sparseVector.getAs[Seq[Float]](SPARSE_VECTOR_VALUES_FIELD_NAME)
        val denseVector = row.getAs[Seq[Float]](FEATURE_WORDS_WIDE_FEATURES_DENSE_COL_NAME)
        indices.zip(values).forall(x => denseVector(x._1.toInt) == x._2)
      }
    }

    TensorizeInJobHelper.saveDataToHDFS(convertedDataFrame, tensorizeInParams)
    assertTrue(new File(s"${tensorizeInParams.workingDir.trainingDataPath}/_SUCCESS").exists())
  }
}