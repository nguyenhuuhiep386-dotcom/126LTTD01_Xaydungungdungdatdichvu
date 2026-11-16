package vn.servio.partner.data.repository

import vn.servio.core.network.ApiResult
import vn.servio.core.network.apiCall
import vn.servio.core.network.apiCallNoContent
import vn.servio.partner.data.model.AddDocumentRequest
import vn.servio.partner.data.model.AddSkillRequest
import vn.servio.partner.data.model.DocumentType
import vn.servio.partner.data.model.PartnerDocument
import vn.servio.partner.data.model.PartnerMe
import vn.servio.partner.data.model.PartnerSkill
import vn.servio.partner.data.model.UpdatePartnerRequest
import vn.servio.partner.data.model.VerificationStatusResult
import vn.servio.partner.data.remote.PartnerApi
import javax.inject.Inject
import javax.inject.Singleton

/** Partner profile, KYC documents and skills: PS-02 → PS-06, PS-23, PS-24. */
interface PartnerProfileRepository {
    suspend fun getMe(): ApiResult<PartnerMe>
    suspend fun update(request: UpdatePartnerRequest): ApiResult<PartnerMe>
    suspend fun addDocument(type: DocumentType, fileUrl: String): ApiResult<PartnerDocument>
    suspend fun submitVerification(): ApiResult<VerificationStatusResult>
    suspend fun listSkills(): ApiResult<List<PartnerSkill>>
    suspend fun addSkill(request: AddSkillRequest): ApiResult<PartnerSkill>
    suspend fun deleteSkill(skillId: String): ApiResult<Unit>
}

@Singleton
class RemotePartnerProfileRepository @Inject constructor(
    private val api: PartnerApi,
) : PartnerProfileRepository {

    override suspend fun getMe() = apiCall { api.getMe() }

    override suspend fun update(request: UpdatePartnerRequest) = apiCall { api.updateMe(request) }

    override suspend fun addDocument(type: DocumentType, fileUrl: String) =
        apiCall { api.addDocument(AddDocumentRequest(type, fileUrl)) }

    override suspend fun submitVerification() = apiCall { api.submitVerification() }

    override suspend fun listSkills() = apiCall { api.listSkills() }

    override suspend fun addSkill(request: AddSkillRequest) = apiCall { api.addSkill(request) }

    override suspend fun deleteSkill(skillId: String) = apiCallNoContent { api.deleteSkill(skillId) }
}
