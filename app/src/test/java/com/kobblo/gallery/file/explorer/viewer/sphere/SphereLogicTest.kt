package com.kobblo.gallery.file.explorer.viewer.sphere

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SphereLogicTest {
    private fun packet(body: String) = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="r"><rdf:Description rdf:about="" xmlns:GPano="http://ns.google.com/photos/1.0/panorama/" $body/></rdf:RDF></x:xmpmeta>"""

    @Test
    fun `equirectangular XMP makes it a sphere`() {
        val found = SphereProjection.parse(packet("""GPano:ProjectionType="equirectangular" GPano:UsePanoramaViewer="True""""))
        assertNotNull(found)
        assertEquals(1f, found!!.width, 0f)
        assertEquals(0f, found.left, 0f)
    }

    @Test
    fun `the element form counts too`() {
        val xmp = "<rdf:Description><GPano:ProjectionType>equirectangular</GPano:ProjectionType></rdf:Description>"
        assertNotNull(SphereProjection.parse(xmp))
    }

    @Test
    fun `other panoramas and photos with no such tag aren't spheres`() {
        assertNull(SphereProjection.parse(packet("""GPano:ProjectionType="cylindrical"""")))
        assertNull(SphereProjection.parse(packet("""GPano:UsePanoramaViewer="True"""")))
        assertNull(SphereProjection.parse(packet("""dc:title="A wide photo"""")))
    }

    @Test
    fun `a partial sphere says what part it covers`() {
        val found = SphereProjection.parse(
            packet(
                """GPano:ProjectionType="equirectangular" GPano:FullPanoWidthPixels="4000" GPano:FullPanoHeightPixels="2000"
                GPano:CroppedAreaImageWidthPixels="2000" GPano:CroppedAreaImageHeightPixels="500"
                GPano:CroppedAreaLeftPixels="1000" GPano:CroppedAreaTopPixels="750""""
            )
        )!!
        assertEquals(0.25f, found.left, 0.0001f)
        assertEquals(0.375f, found.top, 0.0001f)
        assertEquals(0.5f, found.width, 0.0001f)
        assertEquals(0.25f, found.height, 0.0001f)
    }

    @Test
    fun `nonsense sizes fall back to the whole sphere`() {
        val found = SphereProjection.parse(
            packet(
                """GPano:ProjectionType="equirectangular" GPano:FullPanoWidthPixels="0" GPano:FullPanoHeightPixels="2000"
                GPano:CroppedAreaImageWidthPixels="2000" GPano:CroppedAreaImageHeightPixels="500""""
            )
        )!!
        assertEquals(1f, found.width, 0f)
        assertEquals(1f, found.height, 0f)
    }

    @Test
    fun `a drag moves the picture with the finger`() {
        val camera = SphereCamera()
        camera.drag(dxPx = 100f, dyPx = 100f, viewHeightPx = 1000)
        val degrees = 100f * SphereCamera.DefaultFieldOfView / 1000
        assertEquals(-degrees, camera.yaw, 0.001f)
        assertEquals(degrees, camera.pitch, 0.001f)
    }

    @Test
    fun `it stops at the poles and turns all the way around`() {
        val camera = SphereCamera()
        camera.drag(0f, 100_000f, 1000)
        assertEquals(SphereCamera.MaxPitch, camera.pitch, 0f)
        camera.drag(0f, -200_000f, 1000)
        assertEquals(-SphereCamera.MaxPitch, camera.pitch, 0f)
        camera.drag(50_000f, 0f, 1000)
        assertTrue(camera.yaw in -180f..180f)
    }

    @Test
    fun `zoom stays in range and a reset undoes everything`() {
        val camera = SphereCamera()
        camera.zoom(100f)
        assertEquals(SphereCamera.MinFieldOfView, camera.fieldOfView, 0f)
        camera.zoom(0.01f)
        assertEquals(SphereCamera.MaxFieldOfView, camera.fieldOfView, 0f)
        camera.drag(10f, 10f, 100)
        camera.reset()
        assertEquals(0f, camera.yaw, 0f)
        assertEquals(0f, camera.pitch, 0f)
        assertEquals(SphereCamera.DefaultFieldOfView, camera.fieldOfView, 0f)
    }

    @Test
    fun `the mesh fits short indices and every point is on the sphere`() {
        val mesh = SphereMesh()
        val vertexCount = mesh.vertices.size / SphereMesh.FloatsPerVertex
        assertTrue(vertexCount < 65_536)
        assertTrue(mesh.indices.all { (it.toInt() and 0xFFFF) < vertexCount })
        for (i in 0 until vertexCount) {
            val at = i * SphereMesh.FloatsPerVertex
            val length = mesh.vertices[at] * mesh.vertices[at] + mesh.vertices[at + 1] * mesh.vertices[at + 1] + mesh.vertices[at + 2] * mesh.vertices[at + 2]
            assertEquals(1f, length, 0.001f)
        }
    }

    @Test
    fun `the photo's middle is straight ahead and its top is up`() {
        val mesh = SphereMesh(rings = 2, segments = 4)
        // Row 1 of 0..2 is the equator, and column 2 of 0..4 the middle of the photo.
        val middle = (1 * 5 + 2) * SphereMesh.FloatsPerVertex
        assertEquals(0f, mesh.vertices[middle], 0.001f)
        assertEquals(0f, mesh.vertices[middle + 1], 0.001f)
        assertEquals(-1f, mesh.vertices[middle + 2], 0.001f)
        assertEquals(1f, mesh.vertices[2 * SphereMesh.FloatsPerVertex + 1], 0.001f)
    }

    @Test
    fun `the texture never exceeds the limit`() {
        assertEquals(1, sphereSampleSize(4096, 2048, 4096))
        assertEquals(2, sphereSampleSize(8192, 4096, 4096))
        assertEquals(4, sphereSampleSize(16_384, 8192, 4096))
        assertEquals(2, sphereSampleSize(5000, 2500, 4096))
    }
}
