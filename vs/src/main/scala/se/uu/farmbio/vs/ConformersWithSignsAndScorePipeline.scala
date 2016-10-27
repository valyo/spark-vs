package se.uu.farmbio.vs

import se.uu.farmbio.cp.ICP
import se.uu.farmbio.cp.alg.SVM
import org.apache.spark.rdd.RDD
import org.apache.spark.Logging
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.linalg.{ Vector, Vectors }
import org.openscience.cdk.io.SDFWriter

import java.io.StringWriter

trait ConformersWithSignsAndScoreTransforms {
  def dockWithML(
    receptorPath: String,
    method: Int,
    resolution: Int,
    dsInitPercent: Double,
    calibrationSize: Int,
    numIterations: Int): SBVSPipeline with PoseTransforms
}

object ConformersWithSignsAndScorePipeline extends Serializable {

  private def getLPRDD(poses: String) = {
    val it = SBVSPipeline.CDKInit(poses)

    var res = Seq[(LabeledPoint)]()

    while (it.hasNext()) {
      //for each molecule in the record compute the signature

      val mol = it.next
      val label: String = mol.getProperty("Label")
      val doubleLabel: Double = label.toDouble
      val labeledPoint = new LabeledPoint(doubleLabel, Vectors.parse(mol.getProperty("Signature")))
      res = res ++ Seq(labeledPoint)
    }

    res //return the labeledPoint
  }

  private def getFeatureVector(poses: String) = {
    val it = SBVSPipeline.CDKInit(poses)

    var res = Seq[(Vector)]()

    while (it.hasNext()) {
      //for each molecule in the record compute the FeatureVector
      val mol = it.next
      val Vector = Vectors.parse(mol.getProperty("Signature"))
      res = res ++ Seq(Vector)
    }

    res //return the FeatureVector
  }

  private def labelTopAndBottom(sdfRecord: String, score: Double, scoreHistogram: Array[Double]) = {
    val it = SBVSPipeline.CDKInit(sdfRecord)
    val strWriter = new StringWriter()
    val writer = new SDFWriter(strWriter)
    while (it.hasNext()) {
      val mol = it.next
      val label = score match { //convert labels
        case x if x >= scoreHistogram(7) && x <= scoreHistogram(10) => 1.0
        case x if x >= scoreHistogram(0) && x <= scoreHistogram(5) => 0.0
        case _ => "NAN"
      }

      if (label == 0.0 || label == 1.0) {
        mol.removeProperty("cdk:Remark")
        mol.setProperty("Label", label)
        writer.write(mol)
      }
    }
    writer.close
    strWriter.toString() //return the molecule  
  }

}

private[vs] class ConformersWithSignsAndScorePipeline(override val rdd: RDD[String])
    extends SBVSPipeline(rdd) with ConformersWithSignsAndScoreTransforms {

  override def dockWithML(
    receptorPath: String,
    method: Int,
    resolution: Int,
    dsInitPercent: Double,
    calibrationSize: Int,
    numIterations: Int) = {

    //initializations
    var poses: RDD[String] = null
    var dsTrain: RDD[String] = null
    var dsOne: RDD[(String)] = null
    var ds: RDD[String] = rdd
    var eff: Double = 0.0
    var counter: Int = 1

    do {

      //Step 1
      //Get a sample of the data
      val dsInit = ds.sample(false, dsInitPercent, 1234)

      //Step 2
      //Subtract the sampled molecules from main dataset
      ds = ds.subtract(dsInit)

      //Step 3
      //Mocking the sampled dataset. We already have scores, docking not required
      val dsDock = dsInit

      //Step 4
      //Keeping processed poses
      if (poses == null)
        poses = dsDock
      else
        poses = poses.union(dsDock)

      //Step 5 and 6 Computing dsTopAndBottom
      val parseScoreRDD = dsDock.map(PosePipeline.parseScore(method)).cache
      val parseScoreHistogram = parseScoreRDD.histogram(10)

      val dsTopAndBottom = dsDock.map {
        case (mol) =>
          val score = PosePipeline.parseScore(method)(mol)
          ConformersWithSignsAndScorePipeline.labelTopAndBottom(mol, score, parseScoreHistogram._1)
      }.map(_.trim).filter(_.nonEmpty)
      
      parseScoreRDD.unpersist()
      //Step 7 Union dsTrain and dsTopAndBottom
      if (dsTrain == null)
        dsTrain = dsTopAndBottom
      else
        dsTrain = dsTrain.union(dsTopAndBottom)

      //Converting SDF training set to LabeledPoint required for conformal prediction
      val lpDsTrain = dsTrain.flatMap {
        sdfmol => ConformersWithSignsAndScorePipeline.getLPRDD(sdfmol)
      }

      //Step 8 Training
      //Train icps

      val (calibration, properTraining) = ICP.calibrationSplit(lpDsTrain.cache, calibrationSize)
      //Train ICP
      val svm = new SVM(properTraining.cache, numIterations)
      //SVM based ICP Classifier (our model)
      val icp = ICP.trainClassifier(svm, numClasses = 2, calibration)
      lpDsTrain.unpersist()
      properTraining.unpersist()      
      
      //Converting SDF main dataset (ds) to feature vector required for conformal prediction
      //We also need to keep intact the poses so at the end we know
      //which molecules are predicted as bad and remove them from main set

      val fvDs = ds.flatMap {
        sdfmol =>
          ConformersWithSignsAndScorePipeline.getFeatureVector(sdfmol)
            .map { case (vector) => (sdfmol, vector) }
      }

      //Step 9 Prediction using our model
      val predictions = fvDs.map {
        case (sdfmol, predictionData) => (sdfmol, icp.predict(predictionData, 0.2))
      }

      val dsZero: RDD[(String)] = predictions
        .filter { case (sdfmol, prediction) => (prediction == Set(0.0)) }
        .map { case (sdfmol, prediction) => sdfmol }.cache
      dsOne = predictions
        .filter { case (sdfmol, prediction) => (prediction == Set(1.0)) }
        .map { case (sdfmol, prediction) => sdfmol }.cache
      val dsUnknown: RDD[(String)] = predictions
        .filter { case (sdfmol, prediction) => (prediction == Set(0.0, 1.0)) }
        .map { case (sdfmol, prediction) => sdfmol }
      logInfo("Number of bad mols in cycle " + counter + " are " + dsZero.count)
      logInfo("Number of good mols in cycle " + counter + " are " + dsOne.count)
      logInfo("Number of Unknown mols in cycle " + counter + " are " + dsUnknown.count)
     
      //Step 10 Subtracting {0} moles from dataset
      ds = ds.subtract(dsZero)
      dsZero.unpersist()
      dsOne.unpersist()
      //Computing efficiency for stopping
      val totalCount = sc.accumulator(0.0)
      val singletonCount = sc.accumulator(0.0)

      predictions.foreach {
        case (sdfmol, prediction) =>
          if (prediction.size == 1) {
            singletonCount += 1.0
          }
          totalCount += 1.0
      }

      eff = singletonCount.value / totalCount.value
      logInfo("Efficiency in cycle " + counter + " is " + eff)
      counter = counter + 1
    } while (eff < 0.8 || counter < 4)

    //Docking rest of the dsOne mols
    val dsDockOne = ConformerPipeline.getDockingRDD(receptorPath, method, resolution, false, sc, dsOne)
      //Removing empty molecules caused by oechem optimization problem
      .map(_.trim).filter(_.nonEmpty)

    //Keeping rest of processed poses i.e. dsOne mol poses
    if (poses == null)
      poses = dsDockOne
    else
      poses = poses.union(dsDockOne)

    new PosePipeline(poses, method)

  }

}