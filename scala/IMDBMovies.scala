/*
 * Our objective is to predict the IMDB score of a movie. 
 * We'll use the IMDB 5000 Movie Dataset
 * (see https://www.kaggle.com/deepmatrix/imdb-5000-movie-dataset).
 *
 * The exploratory data analysis
 * (see https://public.tableau.com/views/IMDBMovies_0/FirstGenre?:embed=y&:display_count=yes) 
 * shows the following:
 *
 * 1. Content ratings tend to have very different IMDB scores
 * 2. Some countries (e.g. Russia) tend to produce worse rated movies 
 *  than others (e.g. Brazil). However, the overwhelming majority of movies 
 *  in the dataset come from the U.S.A
 * 3. Most movies in the dataset are English, and there's great variation in 
 *  score by language
 * 4. Budget does not correlate strongly with score. 
 *  Title year has a moderate (Rsquared ~ 0.1) negative correlation with score 
 *  - older movies are scored higher. The gross income and budget of the movie 
 *  are weakly correlated to score.
 * 5. The # of Facebook likes for the actors and director is not really 
 *  correlated with the score
 * 6. The number of critics, number of user reviews, and number of voted 
 *  users are all moderately correlated (between Rsquared~ 0.9 and Rsquared = 
 *  0.17) with score
 * 7. There is moderate variation in the first genre (movies can fall into 
 *  multiple genres) of the movie vs. score.
 */

import org.apache.spark.sql.Row
import org.apache.spark.sql.types.{IntegerType, StructType}
import org.apache.spark.ml.regression.{LinearRegression, LinearRegressionModel}
import org.apache.spark.ml.tuning.{CrossValidator, ParamGridBuilder}
import org.apache.spark.ml.evaluation.RegressionEvaluator
import org.apache.spark.sql.{DataFrame, Dataset, Row}
import edu.mit.csail.db.ml.modeldb.util._
import edu.mit.csail.db.ml.modeldb.client.{ModelDbSyncer, NewProject, SyncableMetrics}
import edu.mit.csail.db.ml.modeldb.client.ModelDbSyncer._

object Main {
  def run(pathToData: String): Unit = {
    // Create the ModelDBSyncer
		ModelDbSyncer.setSyncer(
			new ModelDbSyncer(projectConfig = NewProject(
        "IMDB Movies",
				"hsubrama@mit.edu",
				"Attempt to predict IMDB scores for movies."
			))
		)
    // Extract the genres from the genre column, which looks like this:
    // genre1|genre2|genre3|...
		val extractFirstGenre = udf((col: String) => col.split('|')(0))
		val extractSecondGenre = udf((col: String) => {
				val items = col.split('|').tail
				if (items.length == 0)
						"None"
				else
						items(0)
		})

    // Read the data.
		val df = spark
				.read
				.option("header", true)
				.option("inferSchema", true)
				.option("ignoreLeadingWhiteSpace", true)
				.option("ignoreTrainingWhiteSpace", true)
				.option("nullValue", "None")
				.csv(pathToData)
				.withColumn("first_genre", extractFirstGenre($"genres"))
				.withColumn("second_genre", extractSecondGenre($"genres"))
				.na.fill(0)
		df.schema.foreach(field => println(field.dataType + ": " + field.name))

		// So, we will use the following features in our model.
		// Color, Number of Critics, Gross, Number of User Reviews, 
    // Number of User Votes, Content Rating, Title Year, Country, First Genre, 
    // Second Genre.
		val categoricalCols: Array[String] = Array(
				"color",
				"content_rating",
				"country",
				"first_genre",
				"second_genre"
		)
		val scaledCols = Array(
				"num_critic_for_reviews",
				"gross",
				"num_user_for_reviews",
				"title_year",
				"num_voted_users"
		)

    // Create the feature vector.
		val (preprocessedData, featureVectorNames, _) = FeatureVectorizer(
				df.toDF(),
				categoricalCols,
				Array[String](),
				featuresCol = "features",
				scaledCols = scaledCols,
				scaler = Some(true, true)
		)(spark.sqlContext)

    // Create the train and test sets.
    val Array(train, test) = preprocessedData.randomSplitSync(Array(0.7, 0.3))

    // Define the columns that the model will use.
		val labelCol = "imdb_score"
		val featuresCol = "features"
		val predictionCol = "prediction"

    // Create a regression evaluator.
		def makeEvaluator() = {
				new RegressionEvaluator()
						.setLabelCol(labelCol)
						.setPredictionCol(predictionCol)
		}

    // Function that creates a Linear Regression model, preints out its
    // weights, and returns its predictions on the test set.
		def makeLrModel(train: DataFrame, 
										test: DataFrame, 
										featureVectorNames: Option[Array[String]] = None) = {
				val lr = new LinearRegression()
						.setMaxIter(20)
						.setLabelCol(labelCol)
						.setFeaturesCol(featuresCol)
						
				val eval = makeEvaluator()
				
				val paramGrid = new ParamGridBuilder()
						.addGrid(lr.regParam, Array(0.1, 0.3, 0.5))
						.addGrid(lr.elasticNetParam, Array(0.1, 0.3, 0.8))
						.build()
				 
				val lrCv = new CrossValidator()
					.setEstimator(lr)
					.setEvaluator(eval)
					.setEstimatorParamMaps(paramGrid)
					.setNumFolds(3)
				
				val lrCvModel = lrCv.fitSync(train)
				val lrPredictions = lrCvModel.transformSync(test)
				
        /*
				if (featureVectorNames.isDefined)
						lrCvModel
								.bestModel
								.asInstanceOf[LinearRegressionModel]
								.coefficients
								.toArray
								.zip(featureVectorNames.get)
								.sortWith(_._1.abs > _._1.abs)
								.foreach{ case (coeff, value) => println(s"$value: $coeff")}
        */
				lrPredictions
		}

    // Train and evaluate the model.
		val lrPredictions = makeLrModel(train, test, Some(featureVectorNames)) 
    println("Evaluating " + makeEvaluator().evaluate(lrPredictions))

		// Let's try doing the calculation again and try using the languages as a feature.
		val (preprocessedData2, featureVectorNames2, _) = FeatureVectorizer(
				df.toDF(),
				Array(
						"color",
						"content_rating",
						"country",
						"language",
						"first_genre",
						"second_genre"
				),
				Array[String](),
				featuresCol = "features",
				scaledCols = Array(
						"num_critic_for_reviews",
						"gross",
						"num_user_for_reviews",
						"title_year",
						"num_voted_users"
				),
				scaler = Some(true, true)
		)(spark.sqlContext)
		val Array(train2, test2) = preprocessedData2.randomSplitSync(Array(0.7, 0.3))

		val lrPredictions2 = makeLrModel(train2, test2, Some(featureVectorNames2))
		println("Evaluating " + makeEvaluator().evaluate(lrPredictions2))

		// Using the language decreased the RMS error. Now, let's try again and remove the country and language
		// features.
		val (preprocessedData3, featureVectorNames3, _) = FeatureVectorizer(
				df.toDF(),
				Array(
						"color",
						"content_rating",
						"first_genre",
						"second_genre"
				),
				Array[String](),
				featuresCol = "features",
				scaledCols = Array(
						"num_critic_for_reviews",
						"gross",
						"num_user_for_reviews",
						"title_year",
						"num_voted_users"
				),
				scaler = Some(true, true)
		)(spark.sqlContext)
		val Array(train3, test3) = preprocessedData3.randomSplitSync(Array(0.7, 0.3))

		val lrPredictions3 = makeLrModel(train3, test3, Some(featureVectorNames3))
		println("Evaluating " + makeEvaluator().evaluate(lrPredictions3))
  }
}
