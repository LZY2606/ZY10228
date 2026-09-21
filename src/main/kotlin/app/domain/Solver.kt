package app.domain

import app.domain.Projection.blend
import app.model.ConversionModel
import app.model.Endmember
import app.model.Sample
import app.model.SolveRequest
import app.model.SolveResult
import java.time.Instant

object Solver {

    fun solve(req: SolveRequest, sample: Sample, endmembers: List<Endmember>): SolveResult {
        require(endmembers.map { it.id }.toSet() == req.endmemberIds.toSet()) {
            "端元列表与请求不一致"
        }
        val ordered = req.endmemberIds.map { id -> endmembers.first { it.id == id } }
        val grid = Grid(req.grid)
        val mode = (req.blendMode ?: sample.blendMode).uppercase()

        // 每台仪器用相同的“源口径 -> 求解口径”模型投影；仪器源口径可能不同，
        // 因此逐仪器构造转换（密度、折射率假设全程固定）。
        val views = sample.instruments.map { raw ->
            val conv = ConversionModel(
                fromBasis = raw.basis,
                toBasis = req.conversion.toBasis,
                density = req.conversion.density,
                refractiveIndex = req.conversion.refractiveIndex,
                modelName = req.conversion.modelName
            )
            Projection.project(raw, grid, conv)
        }
        val blended = blend(views, grid.nBins, mode)
        val observedTotal = views.sumOf { it.totalGridBasis }
        val columns = ordered.map { em ->
            val conv = ConversionModel(
                fromBasis = em.distribution.basis,
                toBasis = req.conversion.toBasis,
                density = req.conversion.density,
                refractiveIndex = req.conversion.refractiveIndex,
                modelName = req.conversion.modelName
            )
            Projection.project(em.distribution, grid, conv).bins.toDoubleArray()
        }

        val outcome = Inversion.invert(
            y = blended.bins.toDoubleArray(),
            endColumns = columns,
            endmembers = ordered,
            covered = blended.covered.toBooleanArray(),
            relSigma = req.relSigma,
            corrLength = req.corrLength,
            maxSubsetSize = req.maxSubsetSize,
            maxCandidates = req.maxCandidates
        )

        return SolveResult(
            sampleId = sample.id,
            sampleName = sample.name,
            endmemberIds = ordered.map { it.id },
            grid = req.grid,
            gridEdges = grid.edges.toList(),
            gridCenters = grid.centers.toList(),
            conversion = req.conversion.copy(fromBasis = req.conversion.toBasis),
            blendMode = mode,
            instruments = views,
            blended = blended.bins,
            coveredBins = blended.covered,
            coverageCount = blended.covered.count { it },
            outsideTotalGridBasis = blended.outsideGridBasis,
            blendedTotal = blended.bins.sum(),
            observedTotal = observedTotal,
            fitted = outcome.fitted.toList(),
            residuals = outcome.residuals.toList(),
            weightedResiduals = outcome.weightedResiduals.toList(),
            candidates = outcome.candidates,
            clusters = outcome.clusters,
            diagnostics = outcome.diagnostics,
            createdAt = Instant.now().toString()
        )
    }
}
