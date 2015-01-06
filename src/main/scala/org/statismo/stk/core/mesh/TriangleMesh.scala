package org.statismo.stk.core
package mesh

import org.statismo.stk.core.common._
import org.statismo.stk.core.geometry.{ Point, _3D, Vector }
import org.statismo.stk.core.common.Cell
import scala.reflect.ClassTag
import org.statismo.stk.core.common.BoxedDomain
import scala.collection.mutable

import spire.math.Numeric

case class TriangleCell(ptId1: Int, ptId2: Int, ptId3: Int) extends Cell {
  val pointIds = IndexedSeq(ptId1, ptId2, ptId3)

  def containsPoint(ptId: Int) = ptId1 == ptId || ptId2 == ptId || ptId3 == ptId
}

class TriangleMesh private (meshPoints: IndexedSeq[Point[_3D]], val cells: IndexedSeq[TriangleCell], cellMapOpt: Option[mutable.HashMap[Int, Seq[TriangleCell]]]) 
	extends SpatiallyIndexedFiniteDiscreteDomain[_3D](meshPoints, meshPoints.size) {

  // a map that has for every point the neighboring cell ids
  private[this] val cellMap: mutable.HashMap[Int, Seq[TriangleCell]] = cellMapOpt.getOrElse(mutable.HashMap())

  private[this] def updateCellMapForPtId(ptId: Int, cell: TriangleCell): Unit = {
    val cellsForKey = cellMap.getOrElse(ptId, Seq[TriangleCell]())
    cellMap.update(ptId, cellsForKey :+ cell)
  }

  if (!cellMapOpt.isDefined)
    for (cell <- cells) {
      cell.pointIds.foreach(id => updateCellMapForPtId(id, cell))
    }
 
  def cellsWithPt(ptId: Int) = cells.filter(_.containsPoint(ptId))

  def boundingBox: BoxedDomain[_3D] = {
    val minx = points.map(_(0)).min
    val miny = points.map(_(1)).min
    val minz = points.map(_(2)).min
    val maxx = points.map(_(0)).max
    val maxy = points.map(_(1)).max
    val maxz = points.map(_(2)).max
    BoxedDomain[_3D](Point(minx, miny, minz), Point(maxx, maxy, maxz))
  }

  def warp(transform: Point[_3D] => Point[_3D]) = new TriangleMesh(meshPoints.par.map(transform).toIndexedSeq, cells, Some(cellMap))

  def neighbourCells(id: Int): Seq[TriangleCell] = cellMap(id)

  def computeCellNormal(cell: TriangleCell): Vector[_3D] = {
    val pt1 = meshPoints(cell.ptId1)
    val pt2 = meshPoints(cell.ptId2)
    val pt3 = meshPoints(cell.ptId3)

    val u = pt2 - pt1
    val v = pt3 - pt1
    Vector.crossproduct(u, v)
  }

  def normalAtPoint(pt: Point[_3D]): Vector[_3D] = {
    val closestMeshPtId = findClosestPoint(pt)._2
    val neigborCells = neighbourCells(closestMeshPtId)
    val normalUnnormalized = neigborCells.foldLeft(Vector(0f, 0f, 0f))((acc, cell) => acc + computeCellNormal(cell)) * (1.0 / neigborCells.size)
    normalUnnormalized * (1.0 / normalUnnormalized.norm)
  }

  lazy val area = cells.map(triangle => computeTriangleArea(triangle)).sum

  def computeTriangleArea(t: TriangleCell): Double = {
    // compute are of the triangle using heron's formula
    val A = meshPoints(t.ptId1)
    val B = meshPoints(t.ptId2)
    val C = meshPoints(t.ptId3)
    val a = (B - A).norm
    val b = (C - B).norm
    val c = (C - A).norm
    val s = (a + b + c) / 2
    val areaSquared = s * (s - a) * (s - b) * (s - c)
    // it can happen that the area is negative, due to a degenerate triangle.
    if (areaSquared <= 0.0) 0.0 else math.sqrt(areaSquared)
  }

  def samplePointInTriangleCell(t: TriangleCell, seed: Int): Point[_3D] = {
    val A = meshPoints(t.ptId1).toVector
    val B = meshPoints(t.ptId2).toVector
    val C = meshPoints(t.ptId3).toVector

    val rand = new scala.util.Random(seed)
    val u = rand.nextFloat()
    val d = rand.nextFloat()
    val v = if (d + u <= 1) d else 1 - u

    val s = A * u + B * v + C * (1 - (u + v))
    Point(s(0), s(1), s(2))
  }

  override lazy val hashCode = super.hashCode
}

object TriangleMesh {
  def apply(meshPoints: IndexedSeq[Point[_3D]], cells: IndexedSeq[TriangleCell]) = new TriangleMesh(meshPoints, cells, None)
}

case class ScalarMeshData[S: Numeric: ClassTag](mesh: TriangleMesh, values: Array[S]) extends ScalarPointData[_3D, S] {
  require(mesh.numberOfPoints == values.size)

  override def numeric = implicitly[Numeric[S]]

  override val domain = mesh

  override def map[S2: Numeric: ClassTag](f: S => S2): ScalarMeshData[S2] = {
    ScalarMeshData(mesh, values.map(f))
  }
}

