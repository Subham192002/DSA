package com.alfaris.va.escrow.onboarding.service;

import com.alfaris.va.escrow.onboarding.client.AuditLogUtil;
import com.alfaris.va.escrow.onboarding.dto.*;
import com.alfaris.va.escrow.onboarding.entity.*;
import com.alfaris.va.escrow.onboarding.entity.validator.CustomSize;
import com.alfaris.va.escrow.onboarding.repository.*;
import com.alfaris.va.escrow.onboarding.repository.specification.AccountInclusionDetailSpecs;
import com.alfaris.va.escrow.onboarding.repository.specification.ProjectDocumentDefinitionSpecs;
import com.alfaris.va.escrow.onboarding.util.AuditFuctions;
import com.alfaris.va.escrow.onboarding.util.Constants;
import com.alfaris.va.escrow.onboarding.util.FileNetUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.itextpdf.text.*;
import com.itextpdf.text.pdf.BaseFont;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfWriter;
import jakarta.validation.*;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.validator.HibernateValidatorFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.FileCopyUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Principal;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class EscrowProjectServiceImpl implements EscrowProjectService {

    private final UserService userService;
    private final EscrowProjectCustomRepository escrowProjectCustomRepository;
    private final EscrowProjectRepository escrowProjectRepository;
    private final ClientRepository clientRepository;
    private final MessageSource messageSource;
    private final CommisionProfileRepository commisionProfileRepository;
    private final FinacialAccountDetailsRepository finacialAccountDetailsRepository;
    private final ProjectDocumentDefinitionRepository projectDocumentDefinitionRepository;
    private final ProjectDocumentDetailsRepository projectDocumentDetailsRepository;
    private final AccountInclusionDetailsRepository accountInclusionDetailsRepo;
    private final EscrowAccountDetailsRepository escrowAccountDetailsRepository;
    private final CustomerProductService customerProductService;
    private final VirtualAccountDefinitionRepository virtualAccountDefinitionRepository;
    private final AuditLogUtil audit;

    @Value("${FILENET_PROJECT_DIRECTORY}")
    private String uploadDirectory;

    @SuppressWarnings("unchecked")
    @Override
    public JSONObject searchProject(String searchParam, int start, int pageSize, Principal principal) {
        JSONObject res = new JSONObject();
        JSONArray projectArr = new JSONArray();
        try {
            DecimalFormat formatter = new DecimalFormat("#,###.00");
            Pageable pageable = PageRequest.of(start / pageSize, pageSize);
            UserInfoDTO userDto = userService.getUserInfo(principal.getName());
            List<String> projectIdList = userService.getProjectListByDep(userDto.getId().getUserId());
            Specification<ESCProject> spec = EscrowProjectSpecs.searchEscrowProject(searchParam, projectIdList);

            CompletableFuture<List<ESCProjectSearchDTO>> projectFuture = CompletableFuture
                    .supplyAsync(() -> escrowProjectCustomRepository.getListOfProjectsBasedOnSpec(spec, pageable));
            CompletableFuture<Long> compatibleCount = "BNK".equals(userDto.getOrgDept()) ? CompletableFuture.supplyAsync(escrowProjectRepository::count) : CompletableFuture.supplyAsync(() -> escrowProjectRepository.count(
                    EscrowProjectSpecs.searchEscrowProject(searchParam, projectIdList)));
            CompletableFuture<List<CountByStatusDto>> statusCompatible = "BNK".equals(userDto.getOrgDept()) ? CompletableFuture.supplyAsync(escrowProjectRepository::getCountByStatusDefault) : CompletableFuture.supplyAsync(() ->
                    escrowProjectRepository.getCountByStatusWithProjIds(projectIdList));
            CompletableFuture<Void> combinedFuture = CompletableFuture.allOf(projectFuture, compatibleCount, statusCompatible);
            combinedFuture.join();

            for (ESCProjectSearchDTO project : projectFuture.get()) {
                JSONObject projectObj = new JSONObject();
                projectObj.put("projectId", project.getProjectId());
                projectObj.put("projectName", project.getProjectName());
                projectObj.put("crNumber", project.getProjectCrNumber());
                projectObj.put("projectCif", project.getProjectCif());
                projectObj.put("projectValue", Optional.ofNullable(project.getProjectValue())
                        .filter(value -> value.compareTo(BigDecimal.ZERO) > 0)
                        .map(formatter::format)
                        .orElse("NIL"));
                projectObj.put("numberOfUnits", Optional.ofNullable(project.getNumberOfUnits())
                        .filter(u -> u != 0)
                        .map(String::valueOf)
                        .orElse("NIL"));
                projectObj.put("status", project.getStatus());
                projectObj.put("escrowAcc", project.getEscrowAccount());
                projectObj.put("clientName", project.getClientName());
                projectObj.put("totalArea", project.getTotalArea());
                projectObj.put("clientId", project.getClientId());
                projectObj.put("distStatus", project.getDistStatus());
                projectArr.add(projectObj);
            }
            String totalStatus = String.valueOf(compatibleCount.get());
            res.put("totalStatus", totalStatus);
            res.put(Constants.COUNT_BY_STATUS, getCountByStatusAsMap(statusCompatible.get(), totalStatus));
            res.put("aaData", projectArr);
            res.put("iTotalDisplayRecords", compatibleCount.get());
            res.put("iTotalRecords", compatibleCount.get());
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return res;
    }

    private Map<String, String> getCountByStatusAsMap(List<CountByStatusDto> countByStatusDto, String totalStatus) {
        Map<String, String> resultMap = new HashMap<>(16);
        Stream.of(Constants.GridStatus.CACHED_VALUES)
                .forEach(status -> resultMap.put(status.getCode().toLowerCase(Locale.ROOT), "0"));
        if (countByStatusDto != null) {
            countByStatusDto.stream()
                    .filter(dto -> dto.getStatus() != null)
                    .forEach(dto -> {
                        String key = dto.getStatus().toLowerCase(Locale.ROOT);
                        if (resultMap.containsKey(key)) {
                            String value = (dto.getCount() != null) ? dto.getCount() : "0";
                            resultMap.put(key, value);
                        }
                    });
        }
        resultMap.put("totalStatus", totalStatus);
        return resultMap;
    }

    @Override
    public List<Map<String, String>> getDistinctClientIdForRMSVerified() {

        try {
            return clientRepository.getDistinctClientIdForEscrowVerfied();
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    @Override
    public ServiceResponse createValidate(ESCProjectDto projectDto, Principal principle) {
        List<JSONObject> errorList = new ArrayList<>();
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        HibernateValidatorFactory hibernateValidatorFactory = factory.unwrap(HibernateValidatorFactory.class);
        Validator validator = hibernateValidatorFactory.getValidator();

        try {

            if (projectDto.getProjectCif() == null || projectDto.getProjectCif().isEmpty()) {
                JSONObject error = new JSONObject();
                error.put("projectCif", messageSource.getMessage("NotEmpty.ESCProjectDto.projectCif", null, LocaleContextHolder.getLocale()));
                errorList.add(error);
            } else {

                if (projectDto.getProjectCif().length() < 5 || projectDto.getProjectCif().length() > 9) {
                    JSONObject error = new JSONObject();
                    error.put("projectCif", messageSource.getMessage("CustomSize.ESCProjectDto.projectCif", null, LocaleContextHolder.getLocale()));
                    errorList.add(error);
                }
                if (projectDto.getProjectCif().matches("[0-9]")) {
                    JSONObject error = new JSONObject();
                    error.put("projectCif", messageSource.getMessage("Pattern.ESCProjectDto.projectCif", null, LocaleContextHolder.getLocale()));
                    errorList.add(error);
                }
                if (!projectDto.getProjectCif().matches("^\\d+$")) {
                    JSONObject error = new JSONObject();
                    error.put("projectCif", messageSource.getMessage("Pattern.ESCProjectDto.projectCif", null, LocaleContextHolder.getLocale()));
                    errorList.add(error);
                }
            }
            // COMMISSION_LEVEL
            if (projectDto.getCommisionLevel() != null) {
                if (projectDto.getCommisionLevel() == 1) {
                    // COMMISSION PROFILE
                    if (projectDto.getProfileId() == null) {
                        JSONObject error = new JSONObject();
                        error.put("profileId", messageSource.getMessage("NotEmpty.ESCProjectDto.comlevel", null, LocaleContextHolder.getLocale()));
                        errorList.add(error);
                    }

                    // ALTERNATIVE COMMISSION PROFILE
                    if (projectDto.getSecProfileId() != null) {
                        if (projectDto.getProfileExpiryDate() == null) {
                            JSONObject error = new JSONObject();
                            error.put("profileExpiryDate", messageSource.getMessage("NotEmpty.ESCProjectDto.altComExp", null, LocaleContextHolder.getLocale()));
                            errorList.add(error);
                        }
                    }
                }
                if (projectDto.getCommisionLevel() == 1) {
                    if (projectDto.getSecProfileId() != null) {
                        // SAME COMMISSION PROFILE
                        if (projectDto.getSecProfileId().equals(projectDto.getProfileId())) {
                            JSONObject error = new JSONObject();
                            error.put("secProfileId", messageSource.getMessage("NotEmpty.ESCProjectDto.AltComProfileSame", null, LocaleContextHolder.getLocale()));
                            errorList.add(error);
                        }
                    }

                    if (projectDto.getProfileExpiryDate() != null) {
                        // EXPIRY DATE SHOULD NOT BE TODAYS DATE
                        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy");
                        LocalDate today = LocalDate.now();
                        LocalDate altComExp = LocalDate.parse(projectDto.getProfileExpiryDate().toString(), formatter);
                        // FUTURE DATE
                        if (null != altComExp) {
                            if (altComExp.equals(today)) {
                                JSONObject error = new JSONObject();
                                error.put("profileExpiryDate", messageSource.getMessage("NotEmpty.ESCProjectDto.altComExpDate", null, LocaleContextHolder.getLocale()));
                                errorList.add(error);
                            }else if(null != projectDto.getStrStartDate()){
                                LocalDate strDate = LocalDate.parse(projectDto.getStrStartDate().toString(), formatter);
                                if (!altComExp.isAfter(strDate)){
                                    JSONObject error = new JSONObject();
                                    error.put("profileExpiryDate", messageSource.getMessage("NotEmpty.ESCProjectDto.altComExpDateFuture", null, LocaleContextHolder.getLocale()));
                                    errorList.add(error);
                                }
                            }
                        }

                    }

                }
            }

            // CLIENT_ID
            Set<ConstraintViolation<ESCProjectDto>> validationErrors2 = validator.validateValue(ESCProjectDto.class, "clientId", projectDto.getClientId());

            if (!validationErrors2.isEmpty()) {

                for (ConstraintViolation<ESCProjectDto> error : validationErrors2) {
                    JSONObject errorDetail = new JSONObject();

                    if (error.getPropertyPath().toString().equals("clientId") && error.getConstraintDescriptor().getAnnotation().annotationType().equals(jakarta.validation.constraints.NotEmpty.class)) {
                        errorDetail.put(error.getPropertyPath().toString(), messageSource.getMessage("NotEmpty.ESCProjectDto.clientId", null, LocaleContextHolder.getLocale()));
                    } else {
                        errorDetail.put(error.getPropertyPath().toString(), error.getMessage());
                    }

                    errorList.add(errorDetail);

                }
            }

            // PROJECT_ID
            Set<ConstraintViolation<ESCProjectDto>> validationErrors = validator.validateValue(ESCProjectDto.class, "projectId", projectDto.getProjectId());
            if (!validationErrors.isEmpty()) {
                for (ConstraintViolation<ESCProjectDto> error : validationErrors) {
                    JSONObject errorDetail = new JSONObject();

                    if (error.getPropertyPath().toString().equals("projectId") && error.getConstraintDescriptor().getAnnotation().annotationType().equals(CustomSize.class)) {
                        errorDetail.put(error.getPropertyPath().toString(), messageSource.getMessage("CustomSize.ESCProjectDto.projectId", null, LocaleContextHolder.getLocale()));

                    } else if (error.getPropertyPath().toString().equals("projectId") && error.getConstraintDescriptor().getAnnotation().annotationType().equals(jakarta.validation.constraints.NotEmpty.class)) {
                        errorDetail.put(error.getPropertyPath().toString(), messageSource.getMessage("NotEmpty.ESCProjectDto.projectId", null, LocaleContextHolder.getLocale()));

                    } else if (error.getPropertyPath().toString().equals("projectId") && error.getConstraintDescriptor().getAnnotation().annotationType().equals(jakarta.validation.constraints.Pattern.class)) {
                        errorDetail.put(error.getPropertyPath().toString(), messageSource.getMessage("Pattern.ESCProjectDto.projectId", null, LocaleContextHolder.getLocale()));

                    } else {
                        errorDetail.put(error.getPropertyPath().toString(), error.getMessage());
                    }
                    errorList.add(errorDetail);

                }
            }

            // PROJECT_NAME
            Set<ConstraintViolation<ESCProjectDto>> validationErrors1 = validator.validateValue(ESCProjectDto.class, "projectName", projectDto.getProjectName());
            if (!validationErrors1.isEmpty()) {

                for (ConstraintViolation<ESCProjectDto> error : validationErrors1) {
                    JSONObject errorDetail = new JSONObject();

                    if (error.getPropertyPath().toString().equals("projectName") && error.getConstraintDescriptor().getAnnotation().annotationType().equals(CustomSize.class)) {

                        errorDetail.put(error.getPropertyPath().toString(), messageSource.getMessage("CustomSize.ESCProjectDto.projectName", null, LocaleContextHolder.getLocale()));

                    } else if (error.getPropertyPath().toString().equals("projectName") && error.getConstraintDescriptor().getAnnotation().annotationType().equals(jakarta.validation.constraints.NotEmpty.class)) {
                        errorDetail.put(error.getPropertyPath().toString(), messageSource.getMessage("NotEmpty.ESCProjectDto.projectName", null, LocaleContextHolder.getLocale()));

                    } else if (error.getPropertyPath().toString().equals("projectName") && error.getConstraintDescriptor().getAnnotation().annotationType().equals(jakarta.validation.constraints.Pattern.class)) {
                        errorDetail.put(error.getPropertyPath().toString(), messageSource.getMessage("Pattern.ESCProjectDto.projectName", null, LocaleContextHolder.getLocale()));
                    } else {
                        errorDetail.put(error.getPropertyPath().toString(), error.getMessage());
                    }

                    errorList.add(errorDetail);

                }
            }

            // PROJECT NAME ARAB
            Set<ConstraintViolation<ESCProjectDto>> validationErrors5 = validator.validateValue(ESCProjectDto.class, "projectNameArb", projectDto.getProjectNameArb());

            if (!validationErrors5.isEmpty()) {

                for (ConstraintViolation<ESCProjectDto> error : validationErrors5) {
                    JSONObject errorDetail = new JSONObject();

                    if (error.getPropertyPath().toString().equals("projectNameArb") && error.getConstraintDescriptor().getAnnotation().annotationType().equals(jakarta.validation.constraints.Pattern.class)) {
                        errorDetail.put(error.getPropertyPath().toString(), messageSource.getMessage("Pattern.ESCProjectDto.projectNameArb", null, LocaleContextHolder.getLocale()));
                    } else if (error.getPropertyPath().toString().equals("projectNameArb") && error.getConstraintDescriptor().getAnnotation().annotationType().equals(Size.class)) {
                        errorDetail.put(error.getPropertyPath().toString(), messageSource.getMessage("Size.ESCProjectDto.projectNameArb", null, LocaleContextHolder.getLocale()));
                    } else {
                        errorDetail.put(error.getPropertyPath().toString(), error.getMessage());
                    }
                    errorList.add(errorDetail);

                }
            }

            // REGA LICENSE NUMBER
            Set<ConstraintViolation<ESCProjectDto>> validationErrors12 = validator.validateValue(ESCProjectDto.class, "licenceNumber", projectDto.getLicenceNumber());

            if (!validationErrors12.isEmpty()) {

                for (ConstraintViolation<ESCProjectDto> error : validationErrors12) {
                    JSONObject errorDetail = new JSONObject();

                    if (error.getPropertyPath().toString().equals("licenceNumber") && error.getConstraintDescriptor().getAnnotation().annotationType().equals(CustomSize.class)) {
                        errorDetail.put(error.getPropertyPath().toString(), messageSource.getMessage("CustomSize.ESCProjectDto.regaLicNo", null, LocaleContextHolder.getLocale()));
                    } else if (error.getPropertyPath().toString().equals("licenceNumber") && error.getConstraintDescriptor().getAnnotation().annotationType().equals(jakarta.validation.constraints.Pattern.class)) {
                        errorDetail.put(error.getPropertyPath().toString(), messageSource.getMessage("Pattern.ESCProjectDto.regaLicNo", null, LocaleContextHolder.getLocale()));
                    } else if (error.getPropertyPath().toString().equals("licenceNumber") && error.getConstraintDescriptor().getAnnotation().annotationType().equals(jakarta.validation.constraints.NotEmpty.class)) {
                        errorDetail.put(error.getPropertyPath().toString(), messageSource.getMessage("NotEmpty.ESCProjectDto.regaLicNo", null, LocaleContextHolder.getLocale()));
                    } else {
                        errorDetail.put(error.getPropertyPath().toString(), error.getMessage());
                    }
                    errorList.add(errorDetail);

                }
            }

//             REGA LICENSE EXP DATE
            Set<ConstraintViolation<ESCProjectDto>> validationErrors11 = validator.validateValue(ESCProjectDto.class, "strRegaLicenceExpDate", projectDto.getStrRegaLicenceExpDate());
            if (!validationErrors11.isEmpty()) {


                for (ConstraintViolation<ESCProjectDto> error : validationErrors11) {
                    JSONObject errorDetail = new JSONObject();

                    if (error.getPropertyPath().toString().equals("strRegaLicenceExpDate") && error.getConstraintDescriptor().getAnnotation().annotationType().equals(jakarta.validation.constraints.NotEmpty.class)) {
                        errorDetail.put(error.getPropertyPath().toString(), messageSource.getMessage("NotEmpty.ESCProjectDto.strRegaExpDate", null, LocaleContextHolder.getLocale()));
                    } else {
                        errorDetail.put(error.getPropertyPath().toString(), error.getMessage());
                    }
                    errorList.add(errorDetail);
                }
            }


            // START DATE
            Set<ConstraintViolation<ESCProjectDto>> validationErrors8 = validator.validateValue(ESCProjectDto.class, "strStartDate", projectDto.getStrStartDate());

            if (!validationErrors8.isEmpty()) {

                for (ConstraintViolation<ESCProjectDto> error : validationErrors8) {
                    JSONObject errorDetail = new JSONObject();

                    if (error.getPropertyPath().toString().equals("strStartDate") && error.getConstraintDescriptor().getAnnotation().annotationType().equals(jakarta.validation.constraints.NotEmpty.class)) {
                        errorDetail.put(error.getPropertyPath().toString(), messageSource.getMessage("NotEmpty.ESCProjectDto.strStartDate", null, LocaleContextHolder.getLocale()));
                    } else {
                        errorDetail.put(error.getPropertyPath().toString(), error.getMessage());
                    }

                    errorList.add(errorDetail);

                }
            }

            //END DATE

            if(null != projectDto.getStrEndDate()){
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy");
                LocalDate startDate = LocalDate.parse(projectDto.getStrStartDate(), formatter);
                LocalDate endDate   = LocalDate.parse(projectDto.getStrEndDate(), formatter);
                if (startDate.equals(endDate)){
                    JSONObject errorDetail = new JSONObject();
                    errorDetail.put("strEndDate",messageSource.getMessage("ESCProjectDto.strEndDate.sameStart",null, LocaleContextHolder.getLocale()));
                    errorList.add(errorDetail);
                }else if (!endDate.isAfter(startDate)) {
                    JSONObject errorDetail = new JSONObject();
                    errorDetail.put("strEndDate",messageSource.getMessage("ESCProjectDto.strEndDate.afterStart",null, LocaleContextHolder.getLocale()));
                    errorList.add(errorDetail);
                }
            }




            // TOTAL PROJECT COST
            Set<ConstraintViolation<ESCProjectDto>> validationErrors3 = validator.validateValue(ESCProjectDto.class, "projectValue", projectDto.getProjectValue());

            if (!validationErrors3.isEmpty()) {

                for (ConstraintViolation<ESCProjectDto> error : validationErrors3) {
                    JSONObject errorDetail = new JSONObject();

                    if (error.getPropertyPath().toString().equals("projectValue") && error.getConstraintDescriptor().getAnnotation().annotationType().equals(jakarta.validation.constraints.NotNull.class)) {
                        errorDetail.put(error.getPropertyPath().toString(), messageSource.getMessage("NotNull.ESCProjectDto.projectValue", null, LocaleContextHolder.getLocale()));
                    } else {
                        errorDetail.put(error.getPropertyPath().toString(), error.getMessage());
                    }

                    errorList.add(errorDetail);

                }
            }
            // NUMBER OF UNITS
            Set<ConstraintViolation<ESCProjectDto>> validationErrors7 = validator.validateValue(ESCProjectDto.class, "numberOfUnits", projectDto.getNumberOfUnits());

            if (!validationErrors7.isEmpty()) {

                for (ConstraintViolation<ESCProjectDto> error : validationErrors7) {
                    JSONObject errorDetail = new JSONObject();

                    if (error.getPropertyPath().toString().equals("numberOfUnits") && error.getConstraintDescriptor().getAnnotation().annotationType().equals(jakarta.validation.constraints.NotNull.class)) {
                        errorDetail.put(error.getPropertyPath().toString(), messageSource.getMessage("NotNull.ESCProjectDto.numberOfUnits", null, LocaleContextHolder.getLocale()));
                    } else {
                        errorDetail.put(error.getPropertyPath().toString(), error.getMessage());
                    }

                    errorList.add(errorDetail);

                }
            }
            // DEVELOPER_NAME_ENGLISH
            Set<ConstraintViolation<ESCProjectDto>> validationErrors9 = validator.validateValue(ESCProjectDto.class, "developerNameEnglish", projectDto.getDeveloperNameEnglish());

            if (!validationErrors9.isEmpty()) {

                for (ConstraintViolation<ESCProjectDto> error : validationErrors9) {
                    JSONObject errorDetail = new JSONObject();

                    if (error.getPropertyPath().toString().equals("developerNameEnglish") && error.getConstraintDescriptor().getAnnotation().annotationType().equals(CustomSize.class)) {

                        errorDetail.put(error.getPropertyPath().toString(), messageSource.getMessage("CustomSize.ESCProjectDto.developerNameEnglish", null, LocaleContextHolder.getLocale()));

                    } else if (error.getPropertyPath().toString().equals("developerNameEnglish") && error.getConstraintDescriptor().getAnnotation().annotationType().equals(jakarta.validation.constraints.Pattern.class)) {
                        errorDetail.put(error.getPropertyPath().toString(), messageSource.getMessage("Pattern.ESCProjectDto.developerNameEnglish", null, LocaleContextHolder.getLocale()));
                    } else if (error.getPropertyPath().toString().equals("developerNameEnglish") && error.getConstraintDescriptor().getAnnotation().annotationType().equals(jakarta.validation.constraints.NotEmpty.class)) {
                        errorDetail.put(error.getPropertyPath().toString(), messageSource.getMessage("NotEmpty.ESCProjectDto.developerNameEnglish", null, LocaleContextHolder.getLocale()));
                    } else {
                        errorDetail.put(error.getPropertyPath().toString(), error.getMessage());
                    }

                    errorList.add(errorDetail);

                }
            }

            // DEVELOPER NAME ARABIC
            Set<ConstraintViolation<ESCProjectDto>> validationErrors10 = validator.validateValue(ESCProjectDto.class, "developerName", projectDto.getDeveloperName());

            if (!validationErrors10.isEmpty()) {

                for (ConstraintViolation<ESCProjectDto> error : validationErrors10) {
                    JSONObject errorDetail = new JSONObject();

                    if (error.getPropertyPath().toString().equals("developerName") && error.getConstraintDescriptor().getAnnotation().annotationType().equals(jakarta.validation.constraints.NotEmpty.class)) {
                        errorDetail.put(error.getPropertyPath().toString(), messageSource.getMessage("NotEmpty.ESCProjectDto.developerName", null, LocaleContextHolder.getLocale()));
                    } else {
                        errorDetail.put(error.getPropertyPath().toString(), error.getMessage());
                    }

                    errorList.add(errorDetail);

                }
            }

            // PROJECT ADDRESS 1
            Set<ConstraintViolation<ESCProjectDto>> validationErrors4 = validator.validateValue(ESCProjectDto.class, "projectSiteAddress1", projectDto.getProjectSiteAddress1());

            if (!validationErrors4.isEmpty()) {

                for (ConstraintViolation<ESCProjectDto> error : validationErrors4) {
                    JSONObject errorDetail = new JSONObject();

                    if (error.getPropertyPath().toString().equals("projectSiteAddress1") && error.getConstraintDescriptor().getAnnotation().annotationType().equals(CustomSize.class)) {
                        errorDetail.put(error.getPropertyPath().toString(), messageSource.getMessage("CustomSize.ESCProjectDto.projectSiteAddress1", null, LocaleContextHolder.getLocale()));
                    } else if (error.getPropertyPath().toString().equals("projectSiteAddress1") && error.getConstraintDescriptor().getAnnotation().annotationType().equals(jakarta.validation.constraints.Pattern.class)) {
                        errorDetail.put(error.getPropertyPath().toString(), messageSource.getMessage("Pattern.ESCProjectDto.projectSiteAddress1", null, LocaleContextHolder.getLocale()));
                    } else if (error.getPropertyPath().toString().equals("projectSiteAddress1") && error.getConstraintDescriptor().getAnnotation().annotationType().equals(jakarta.validation.constraints.NotEmpty.class)) {
                        errorDetail.put(error.getPropertyPath().toString(), messageSource.getMessage("NotEmpty.ESCProjectDto.projectSiteAddress1", null, LocaleContextHolder.getLocale()));
                    } else {
                        errorDetail.put(error.getPropertyPath().toString(), error.getMessage());
                    }
                    errorList.add(errorDetail);

                }
            }

            // PROJECT ADDRESS 2

            Set<ConstraintViolation<ESCProjectDto>> validationErrors13 = validator.validateValue(ESCProjectDto.class, "projectSiteAddress2", projectDto.getProjectSiteAddress2());

            if (!validationErrors13.isEmpty()) {

                for (ConstraintViolation<ESCProjectDto> error : validationErrors13) {
                    JSONObject errorDetail = new JSONObject();

                    if (error.getPropertyPath().toString().equals("projectSiteAddress2") && error.getConstraintDescriptor().getAnnotation().annotationType().equals(CustomSize.class)) {
                        errorDetail.put(error.getPropertyPath().toString(), messageSource.getMessage("CustomSize.ESCProjectDto.projectSiteAddress2", null, LocaleContextHolder.getLocale()));
                    } else if (error.getPropertyPath().toString().equals("projectSiteAddress2") && error.getConstraintDescriptor().getAnnotation().annotationType().equals(jakarta.validation.constraints.Pattern.class)) {
                        errorDetail.put(error.getPropertyPath().toString(), messageSource.getMessage("Pattern.ESCProjectDto.projectSiteAddress2", null, LocaleContextHolder.getLocale()));
                    } else {
                        errorDetail.put(error.getPropertyPath().toString(), error.getMessage());
                    }
                    errorList.add(errorDetail);

                }
            }

            //EMAIL
            Set<ConstraintViolation<ESCProjectDto>> validationErrors14 = validator.validateValue(ESCProjectDto.class, "email", projectDto.getEmail());

            if (!validationErrors14.isEmpty()) {

                for (ConstraintViolation<ESCProjectDto> error : validationErrors14) {
                    JSONObject errorDetail = new JSONObject();

                    if (error.getPropertyPath().toString().equals("email") && error.getConstraintDescriptor().getAnnotation().annotationType().equals(jakarta.validation.constraints.Pattern.class)) {
                        errorDetail.put(error.getPropertyPath().toString(), messageSource.getMessage("Pattern.ESCProjectDto.emailId", null, LocaleContextHolder.getLocale()));
                    } else if (error.getPropertyPath().toString().equals("email") && error.getConstraintDescriptor().getAnnotation().annotationType().equals(jakarta.validation.constraints.NotEmpty.class)) {
                        errorDetail.put(error.getPropertyPath().toString(), messageSource.getMessage("NotEmpty.ESCProjectDto.email", null, LocaleContextHolder.getLocale()));
                    } else {
                        errorDetail.put(error.getPropertyPath().toString(), error.getMessage());
                    }
                    errorList.add(errorDetail);

                }
            }

            // PHONE
            Set<ConstraintViolation<ESCProjectDto>> validationErrors15 = validator.validateValue(ESCProjectDto.class, "phoneNumber", projectDto.getPhoneNumber());

            if (!validationErrors15.isEmpty()) {

                for (ConstraintViolation<ESCProjectDto> error : validationErrors15) {
                    JSONObject errorDetail = new JSONObject();

                    if (error.getPropertyPath().toString().equals("phoneNumber") && error.getConstraintDescriptor().getAnnotation().annotationType().equals(jakarta.validation.constraints.Pattern.class)) {
                        errorDetail.put(error.getPropertyPath().toString(), messageSource.getMessage("Pattern.ESCProjectDto.phoneNumber", null, LocaleContextHolder.getLocale()));
                    } else if (error.getPropertyPath().toString().equals("phoneNumber") && error.getConstraintDescriptor().getAnnotation().annotationType().equals(CustomSize.class)) {
                        errorDetail.put(error.getPropertyPath().toString(), messageSource.getMessage("CustomSize.ESCProjectDto.phoneNumber", null, LocaleContextHolder.getLocale()));
                    } else if (error.getPropertyPath().toString().equals("phoneNumber") && error.getConstraintDescriptor().getAnnotation().annotationType().equals(jakarta.validation.constraints.NotEmpty.class)) {
                        errorDetail.put(error.getPropertyPath().toString(), messageSource.getMessage("NotEmpty.ESCProjectDto.phoneNumber", null, LocaleContextHolder.getLocale()));
                    } else {
                        errorDetail.put(error.getPropertyPath().toString(), error.getMessage());
                    }
                    errorList.add(errorDetail);

                }
            }

            if (!errorList.isEmpty()) {
                return new ServiceResponse("ERROR", messageSource.getMessage("escrowProject.va.VAL0050", null, LocaleContextHolder.getLocale()), errorList);
            }

            return new ServiceResponse("SUCCESS", messageSource.getMessage("escrowProject.va.VAL0047", null, LocaleContextHolder.getLocale()), null);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return new ServiceResponse("VALERRCOD", messageSource.getMessage("escrowProject.va.VAL0028", null, LocaleContextHolder.getLocale()), null);
        }
    }

    @Override
    public JSONObject getCommisionLevel(String clientId, Principal principal) {
        JSONObject res = new JSONObject();
        try {
//            Optional<Client> clientDet = clientRepository.findById(clientId);
            Client clientDet = clientRepository.getCustomerDetails(clientId);
            if (null != clientDet) {
                res.put("clientId", clientDet.getClientId());
                res.put("commisionLevel", clientDet.getCommisionLevel());
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return res;
    }



    @Override
    public JSONArray getProfileIdList() {
        JSONArray arr = new JSONArray();

        try {
            List<String> clientIdArr = commisionProfileRepository.getVerifiedCommissionProfile();
            arr.addAll(clientIdArr);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            arr = new JSONArray();
        }

        return arr;
    }

    @SuppressWarnings("unchecked")
    @Override
    public ServiceResponse saveProject(@Valid ESCProjectDto projectDto, Principal principal) {
        JSONObject res = new JSONObject();
        String userId = principal.getName();
        SimpleDateFormat sdf = null;

        ESCProject newProjectObj = null;
        BigDecimal zeroBigDecimal = null;
        FinacialAccountDetails finacialAccountDetails = null;
        Client client = null;
        boolean saveStatus = false;
        List<JSONObject> details = new ArrayList<>();
        try {

            ESCProject projectObjCheck = escrowProjectRepository.getProject(projectDto.getProjectId());
            if (projectObjCheck != null) {
                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0048", null, LocaleContextHolder.getLocale()), null);
            }

            List<ESCProject>  projectObjArbCheck = escrowProjectRepository.getProjectArbByName(projectDto.getProjectNameArb());
            if (projectObjArbCheck.size() > 0) {
                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL00110", null, LocaleContextHolder.getLocale()), null);
            }

            List<ESCProject> projectObjCheckName = escrowProjectRepository.getProjectByName(projectDto.getProjectName());
            if (projectObjCheckName.size() > 0) {
                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0071", null, LocaleContextHolder.getLocale()), null);
            }

            projectDto.setCreatedId(userId);
            projectDto.setRegaLicenseExpDate(stringToDateConverter(projectDto.getStrRegaLicenceExpDate()));
            projectDto.setFundRequestWorkflow(Constants.ESCROW_PROJECT.WF_FR);
            projectDto.setWorkflowCreation(Constants.ESCROW_PROJECT.WF_PC);
            projectDto.setWorkflowActivation(Constants.ESCROW_PROJECT.WF_PA);
            projectDto.setClosureWorkflow(Constants.ESCROW_PROJECT.WF_PE);

            BigDecimal bdblretension = calcalutePercentage(projectDto.getProjectValue(), Constants.ESCROW_PROJECT.CAP_RET_PER);
            BigDecimal bdblmarketing = calcalutePercentage(projectDto.getProjectValue(), Constants.ESCROW_PROJECT.CAP_MAR_PER);
            BigDecimal bdblconstruction = calcalutePercentage(projectDto.getProjectValue(), Constants.ESCROW_PROJECT.CAP_CON_PER);
            projectDto.setProjectRetentionValue(bdblretension);
            projectDto.setProjectConsValue(bdblconstruction);
            projectDto.setProjectMarketingValue(bdblmarketing);
            projectDto.setDistStatus(0);

            String workFlowName = projectDto.getWorkflowCreation();
            sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            ESCProject projectObj = escrowProjectRepository.getProject(projectDto.getProjectId());
            client = clientRepository.getCustomerDetails(projectDto.getClientId());
            if (!StringUtils.isEmpty(projectObj)) {
                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0008", null, LocaleContextHolder.getLocale()), null);
            } else if (!isArabic(projectDto.getProjectNameArb())) {
                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0007", null, LocaleContextHolder.getLocale()), null);
            } else if (!isArabic(projectDto.getDeveloperName())) {
                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0006", null, LocaleContextHolder.getLocale()), null);
            } else if (StringUtils.isEmpty(client)) {
                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0004", null, LocaleContextHolder.getLocale()), null);
            } else if (null!=client.getSchemeCode() && client.getSchemeCode() > 99) {

                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0003", null, LocaleContextHolder.getLocale()), null);

            } else {
                newProjectObj = new ESCProject();
                finacialAccountDetails = new FinacialAccountDetails();
                zeroBigDecimal = BigDecimal.valueOf(0.00);

                /*-------saving Project----------------*/

                projectDto.setStatus("INIDEF");
                projectDto.setProjStartDate(stringToDateConverter(projectDto.getStrStartDate()));
                projectDto.setEndDate(stringToDateConverter(projectDto.getStrEndDate()));
                BeanUtils.copyProperties(projectDto, newProjectObj);

                DateFormat formatProf = new SimpleDateFormat("dd-MM-yyyy");
                if (null != projectDto.getProfileExpiryDate()) {
                    newProjectObj.setProfileExpiryDate(formatProf.parse(projectDto.getProfileExpiryDate()));
                }
                newProjectObj.setDateCreated(new Date());
                newProjectObj.setLastSchmeDate(new Date());
                newProjectObj.setSchemeId(projectDto.getProjectId());
                newProjectObj.setClientId(projectDto.getClientId());
                newProjectObj.setCreatedId(principal.getName());
                newProjectObj.setPhoneNumber(projectDto.getPhoneNumber());

                /*-------saving To finacial Table----------------*/

                finacialAccountDetails.setConstructionAccount(zeroBigDecimal);
                finacialAccountDetails.setCurrentConstructionAccount(zeroBigDecimal);
                finacialAccountDetails.setCurrentMarkettingAccount(zeroBigDecimal);
                finacialAccountDetails.setMarkettingAccount(zeroBigDecimal);
                finacialAccountDetails.setCurrentRetentionAccount(zeroBigDecimal);
                finacialAccountDetails.setRetentionAccount(zeroBigDecimal);
                finacialAccountDetails.setSurplusAccount(zeroBigDecimal);
                finacialAccountDetails.setCurrentSurplusAccount(zeroBigDecimal);
                finacialAccountDetails.setProDate(new Date());
                finacialAccountDetails.setProjectId(projectDto.getProjectId());
                finacialAccountDetails.setCurrentLoanAmount(zeroBigDecimal);
                finacialAccountDetails.setCurrentInvestmentAmount(zeroBigDecimal);
                finacialAccountDetails.setInvestmentAmount(zeroBigDecimal);
                finacialAccountDetails.setLoanAmount(zeroBigDecimal);
                finacialAccountDetails.setBankFinanceAmount(zeroBigDecimal);
                finacialAccountDetails.setCurrentBankFinanceAmount(zeroBigDecimal);
                finacialAccountDetails.setBankHafizAmount(zeroBigDecimal);
                finacialAccountDetails.setCurrentHafizAmount(zeroBigDecimal);
                finacialAccountDetails.setBankPosAmount(zeroBigDecimal);
                finacialAccountDetails.setCurrentPosAmount(zeroBigDecimal);

                saveStatus = escrowProjectRepository.save(newProjectObj) != null ? true : false;
                saveStatus = finacialAccountDetailsRepository.save(finacialAccountDetails) != null ? true : false;
                saveStatus = clientRepository.save(client) != null ? true : false;

                if (saveStatus) {
                    res.put("escrowCif", newProjectObj.getProjectCif());
                    res.put("custCifNo", client.getCustomerBaseNumber());
                    res.put("wfReqId", "000");

                    details.add(res);

                    ESCProjectAuditDto escProjectAuditDto = new ESCProjectAuditDto();
                    BeanUtils.copyProperties(newProjectObj,escProjectAuditDto);
                    JsonObject newEntityAudit = new JsonParser().parse(getJsonObject(escProjectAuditDto).toJSONString())
                            .getAsJsonObject();

                    audit.creatAudit(null, newEntityAudit.toString(), AuditFuctions.CREATE, Constants.SCREEN_ID.PROJECT_PROFILE, client.getClientId(), Constants.MESSAGE_STATUS.PROCESSED, messageSource.getMessage("escrowProject.va.VAL0001", null, LocaleContextHolder.getLocale()), principal);

                    return new ServiceResponse(Constants.MESSAGE_STATUS.SUCCESS, messageSource.getMessage("escrowProject.va.VAL0001", null, LocaleContextHolder.getLocale()), details);

                } else {
                    return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0002", null, LocaleContextHolder.getLocale()), null);

                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0002", null, LocaleContextHolder.getLocale()), null);
        }

    }


    private boolean isArabic(String name) {
        if (name != null) {
            for (int i = 0; i < name.length(); ) {
                int c = name.codePointAt(i);
                if (c >= 0x0600 && c <= 0x06E0) return true;
                i += Character.charCount(c);
            }
        }
        return false;
    }

    public BigDecimal calcalutePercentage(BigDecimal totalAmount, Double percentage) {
        return totalAmount.multiply(new BigDecimal(percentage)).divide(new BigDecimal(100));

    }

    public Date stringToDateConverter(String strDate) {
        DateFormat dateformate = new SimpleDateFormat("dd-MM-yyyy");
        if (strDate == null || strDate.isEmpty()) {
            return null;
        }
        Date convertDate = new Date();
        try {
            convertDate = dateformate.parse(strDate);
//            String newDateString = dateformate.format(convertDate);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return convertDate;
    }

    private JSONObject getJsonObject(ESCProject escProject) {
        try {
            ObjectMapper mapper = new ObjectMapper();

            mapper.registerModule(new JavaTimeModule());
            mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            String jsonString = mapper.writeValueAsString(escProject);
            JSONParser parser = new JSONParser();
            JSONObject json = (JSONObject) parser.parse(jsonString);

            return json;

        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return new JSONObject();
    }

    private JSONObject getJsonObject(ESCProjectAuditDto escProjectAuditDto) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            String jsonString = mapper.writeValueAsString(escProjectAuditDto);
            JSONParser parser = new JSONParser();
            return (JSONObject) parser.parse(jsonString);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return new JSONObject();
    }
    @SuppressWarnings("unchecked")
    @Override
    public JSONObject searchProjectDocument(String searchParam, int start, int pageSize, Principal principal) {
        JSONObject res = new JSONObject();
        JSONArray projectArr = new JSONArray();

        long rowCount = 0;
        String apprLevel = "";
        String wfRequestId = null;
        String ProjectId = "";
        JSONParser parser = new JSONParser();
        try {
            if (!StringUtils.isEmpty(searchParam)) {
                JSONObject searchObject = (JSONObject) parser.parse(searchParam);

                Object apprObj = searchObject.get("apprLevel");
                apprLevel = apprObj == null ? null : String.valueOf(apprObj);

                Object projObj = searchObject.get("projectId");
                ProjectId = projObj == null ? null : String.valueOf(projObj);

                if (searchObject.get("wfRequestId") != null) {
                    wfRequestId = (String) searchObject.get("wfRequestId");
                }
            }
            int count = start;

            Pageable pageable = PageRequest.of(start / pageSize, pageSize);

            Page<ProjectDocumentDefinition> projectList = projectDocumentDefinitionRepository.findAll(ProjectDocumentDefinitionSpecs.searchProjectDocumentDefinition(searchParam), pageable);
            rowCount = (long) projectDocumentDefinitionRepository.findAll(ProjectDocumentDefinitionSpecs.searchProjectDocumentDefinition(searchParam)).size();

            for (ProjectDocumentDefinition escProjectDocument : projectList) {
                JSONObject projectObj = new JSONObject();
                count = count + 1;
                projectObj.put("number", count);
                projectObj.put("documentId", escProjectDocument.getId().getDocumentId());
                projectObj.put("documentName", escProjectDocument.getId().getDocumentName());
                Integer docListNo = projectDocumentDetailsRepository.getProjectDocumentDetailsByPK(ProjectId, escProjectDocument.getId().getDocumentName(), apprLevel, wfRequestId);
                projectObj.put("noOfDocs", docListNo);
                if (escProjectDocument.getOptionFalg() != null && escProjectDocument.getOptionFalg() == 1) {
                    projectObj.put("optFlag", "Mandatory");
                } else {
                    projectObj.put("optFlag", "Optional");
                }
                projectObj.put("optionFlag", "");
                projectObj.put("fileName", "<button class='btn btn-success show_files glyphicon glyphicon-folder-open' type='button' code=''></button>");
                projectArr.add(projectObj);

            }
            projectArr.sort(new MyJSONComparator());

            res.put("aaData", projectArr);
            res.put("iTotalDisplayRecords", rowCount);
            res.put("iTotalRecords", rowCount);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return res;
    }

    public static class MyJSONComparator implements Comparator<JSONObject> {
        @Override
        public int compare(JSONObject s1, JSONObject s2) {
            return Integer.parseInt(s2.get("noOfDocs").toString()) - Integer.parseInt(s1.get("noOfDocs").toString());
        }
    }

    @Override
    public ServiceResponse uploadProjectRelatedDocuments(MultipartFile document, String projectId, String documentId, String documentName, Principal principal) {

        JSONObject res = new JSONObject();
        ProjectDocumentDetails uploadStatus = null;
        ProjectDocumentDetails projectDocuments = null;
        try {

            String orginalFileName = document.getOriginalFilename();
            String fileExtn = getFileExtn(orginalFileName);
            if (null != fileExtn && !fileExtn.equals("")) {
                boolean status = validateExtension(fileExtn);
                if (!status) {
                    return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0096", null, LocaleContextHolder.getLocale()), null);
                }
            } else if (fileExtn.equals("INVALID")) {
                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0097", null, LocaleContextHolder.getLocale()), null);
            } else {
                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0096", null, LocaleContextHolder.getLocale()), null);
            }

            String fileName = formatFileName(document.getOriginalFilename(), projectId);
            String fileContent = Base64.getEncoder().encodeToString(document.getBytes());


            projectDocuments = projectDocumentDetailsRepository.getDocumentDetails(projectId, documentId, documentName, fileName);

            String uploadDir = uploadDirectory;
            java.nio.file.Path path = Paths.get(uploadDir + File.separator + fileName);
            Files.copy(document.getInputStream(), path);
            String filePath = uploadDir + "/" + fileName;
            Path fullPath = Paths.get(filePath);
            String extension = getFileExtension(fullPath);
            projectDocuments = new ProjectDocumentDetails();
            ProjectDocumentDetailsPK projDocPK = new ProjectDocumentDetailsPK(projectId, documentId, documentName, fileName);

            projectDocuments.setId(projDocPK);
            projectDocuments.setDatecreated(new Date());
            projectDocuments.setFileContent(fileContent);
            projectDocuments.setCreatedBy(principal.getName());
            uploadStatus = projectDocumentDetailsRepository.save(projectDocuments);
            if (!StringUtils.isEmpty(uploadStatus)) {
                String clientId = escrowProjectRepository.getPorjectClientID(projectId);
                String docId = FileNetUtil.uploadDocWithFileNet(filePath, fileName, extension, clientId, projectId);
                if (docId.equals("") || null == docId) {
                    log.info("Failed Upload to filenet in Project ");
                } else {
                    log.info("File Net ID ::::::  {}", docId);
                    projectDocuments.setFileNetId(docId);
                    projectDocumentDetailsRepository.save(projectDocuments);
                }
                return new ServiceResponse(Constants.MESSAGE_STATUS.SUCCESS, messageSource.getMessage("escrowProject.va.VAL0022", null, LocaleContextHolder.getLocale()), null);

            } else {

                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0021", null, LocaleContextHolder.getLocale()), null);

            }

        } catch (Exception e) {
            log.error(e.getMessage(), e);

            return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0021", null, LocaleContextHolder.getLocale()), null);
        }

    }

    public static String getFileExtension(Path path) {
        String fileName = path.getFileName().toString();
        int lastIndexOfDot = fileName.lastIndexOf(".");
        if (lastIndexOfDot == -1) {
            return "";
        }
        return fileName.substring(lastIndexOfDot + 1);
    }

    private String getFileExtn(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < fileName.length() - 1) {
            String extn = fileName.substring(dotIndex + 1).toLowerCase();
            String rem = fileName.substring(0, dotIndex).toLowerCase();
            if (rem.contains(".")) {
                return "INVALID";
            } else {
                return extn;
            }
        }
        return "";
    }

    private boolean validateExtension(String extension) {
        String[] allowedExt = {"pdf", "xlsx", "docx", "png", "jpg", "xls", "doc"};
        for (String allowed : allowedExt) {
            if (allowed.equals(extension)) {
                return true;
            }
        }
        return false;
    }

    private String formatFileName(String fileName, String projectId) {
        fileName = fileName.replaceAll(":", "_");
        fileName = String.format("%s%s%s%s%s", projectId, "_", new SimpleDateFormat("yyyyMMddHHmmss").format(new Date()), "_", fileName);
        return fileName;
    }

    @SuppressWarnings("unchecked")
    @Override
    public JSONArray getProjectDocumentDetailsByPK(String projectId, String documentName, String apprLevel, String wfRequestId, Principal principal) {
        JSONArray groupArray = new JSONArray();
        JSONObject jsonObjSingleUploadedFile = null;
        List<ProjectDocumentDetails> documentList = null;
        try {

            documentList = projectDocumentDetailsRepository.getDocumentDetailsByPK(projectId, apprLevel, documentName, wfRequestId);

            if (documentList != null) {
                for (ProjectDocumentDetails escProjectDocumentDetail : documentList) {
                    jsonObjSingleUploadedFile = new JSONObject();
                    log.info("File Name:::{}", escProjectDocumentDetail.getId().getFileName());
                    jsonObjSingleUploadedFile.put("doc_name", escProjectDocumentDetail.getId().getDocumentName());
                    jsonObjSingleUploadedFile.put("projectId", escProjectDocumentDetail.getId().getProjectId());
                    jsonObjSingleUploadedFile.put("apprLevel", escProjectDocumentDetail.getId().getApprLevel());
                    jsonObjSingleUploadedFile.put("fileName", escProjectDocumentDetail.getId().getFileName());
                    jsonObjSingleUploadedFile.put("full_doc_path", escProjectDocumentDetail.getFileContent());
                    jsonObjSingleUploadedFile.put("doc_nameExtn", escProjectDocumentDetail.getId().getFileName().substring(escProjectDocumentDetail.getId().getFileName().lastIndexOf(".") + 1, escProjectDocumentDetail.getId().getFileName().length()));
                    groupArray.add(jsonObjSingleUploadedFile);
                }
            } else {
                groupArray = new JSONArray();
            }

        } catch (Exception ex) {
            log.error("Error in get View Of Single Uploaded File {} {}", ex.getMessage(), ex);
        }
        return groupArray;

    }

    @SuppressWarnings("unchecked")
    @Override
    public JSONObject searchAccountInclusionList(String searchParam, int start, int pageSize, Principal principal) {
        JSONObject res = new JSONObject();
        JSONArray projectArr = new JSONArray();
        long rowCount = 0;
        String ProjectId = null;
        JSONParser parser = new JSONParser();
        int count = 1;
        try {
            if (!StringUtils.isEmpty(searchParam)) {
                JSONObject searchObject = (JSONObject) parser.parse(searchParam);

                Object projObj = searchObject.get("projectId");
                ProjectId = projObj == null ? null : String.valueOf(projObj);

            }

            if (!StringUtils.isEmpty(ProjectId)) {

                Pageable pageable = PageRequest.of(start / pageSize, pageSize);

                Page<AccountInclusionDetails> accountIncList = accountInclusionDetailsRepo.findAll(AccountInclusionDetailSpecs.searchAccountInclusionDetails(searchParam), pageable);
                rowCount = accountInclusionDetailsRepo.findAll(AccountInclusionDetailSpecs.searchAccountInclusionDetails(searchParam)).size();

                for (AccountInclusionDetails accountInclusionDetails : accountIncList) {
                    JSONObject accountDetails = new JSONObject();
                    accountDetails.put("slNo", count);
                    accountDetails.put("ibanAccountNumber", accountInclusionDetails.getId().getIbanAccountNumber());
                    accountDetails.put("accountName", accountInclusionDetails.getAccountName());
                    accountDetails.put("description", accountInclusionDetails.getDescription());
                    if (accountInclusionDetails.getFundType() == 1) {
                        accountDetails.put("fundType", "Self Finance");
                    } else if (accountInclusionDetails.getFundType() == 2) {
                        accountDetails.put("fundType", "Loan");
                    } else if (accountInclusionDetails.getFundType() == 4) {
                        accountDetails.put("fundType", "POS");
                    } else if (accountInclusionDetails.getFundType() == 5) {
                        accountDetails.put("fundType", "HAFIS");
                    } else {
                        accountDetails.put("fundType", "Bank Finance");

                    }

                    if (accountInclusionDetails.getDstType() == 0) {
                        accountDetails.put("dstType", "Yes");
                    } else {
                        accountDetails.put("dstType", "No");

                    }

                    accountDetails.put("projectId", accountInclusionDetails.getId().getProjectId());
                    accountDetails.put("action", accountInclusionDetails.getId().getIbanAccountNumber());
                    projectArr.add(accountDetails);
                    count++;
                }
            }

            res.put("aaData", projectArr);
            res.put("iTotalDisplayRecords", rowCount);
            res.put("iTotalRecords", rowCount);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return res;
    }

    @Override
    public ServiceResponse deleteInclusionAccount(String projectId, String accountNo, Principal principal) {
        JSONObject arr = new JSONObject();
        AccountInclusionDetails inclusionDetails = null;
        boolean deleteStatus = false;
        try {
            inclusionDetails = accountInclusionDetailsRepo.checkInclusionAccountDefined(projectId, accountNo);

            if (StringUtils.isEmpty(inclusionDetails)) {

                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0023", null, LocaleContextHolder.getLocale()), null);

            } else {

                String resp = getJsonObject(inclusionDetails).toJSONString();

                accountInclusionDetailsRepo.delete(inclusionDetails);
                deleteStatus = accountInclusionDetailsRepo.checkInclusionAccountDefined(projectId, accountNo) == null ? true : false;
                if (deleteStatus) {

                    audit.creatAudit(null, resp, AuditFuctions.DELETE, Constants.SCREEN_ID.PROJECT_PROFILE, "NIL", Constants.MESSAGE_STATUS.DELETE, messageSource.getMessage("escrowProject.va.VAL0024", null, LocaleContextHolder.getLocale()), principal);

                    return new ServiceResponse(Constants.MESSAGE_STATUS.SUCCESS, messageSource.getMessage("escrowProject.va.VAL0024", null, LocaleContextHolder.getLocale()), null);
                } else {
                    return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0023", null, LocaleContextHolder.getLocale()), null);
                }

            }

        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0023", null, LocaleContextHolder.getLocale()), null);

        }

    }

    private JSONObject getJsonObject(AccountInclusionDetails accountInclusionDetails) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            String jsonString = mapper.writeValueAsString(accountInclusionDetails);
            JSONParser parser = new JSONParser();
            JSONObject json = (JSONObject) parser.parse(jsonString);
            return json;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return new JSONObject();
    }

    @SuppressWarnings("unchecked")
    @Override
    public ServiceResponse saveInclusionDetails(@Valid AccountInclusionDetailsDto inclusionaccDetailsDto, Principal principal) {
        JSONObject arr = new JSONObject();
        AccountInclusionDetails inclusionDetails = null;
        boolean saveStatus = false;
        JSONObject res = new JSONObject();
        String soapResponse = "SUCCESS";        //FIX
        String IBAN = "";
        String BBAN = "";
        List<JSONObject> errorList = new ArrayList<>();
        JSONObject cic = null;
        boolean error = false;

        try {
            if ((inclusionaccDetailsDto.getProjectType() == 1 && !inclusionaccDetailsDto.getBucketFlag().contains("V")) || inclusionaccDetailsDto.getBucketFlag().contains("P")) {
                if (inclusionaccDetailsDto.getIbanAccountNumber() == null || inclusionaccDetailsDto.getIbanAccountNumber().isEmpty()) {
                    error = true;
                    cic = new JSONObject();
                    cic.put("ibanAccountNumber", messageSource.getMessage("NotEmpty.accountInclusionDetailsDto.ibanAccountNumber", null, LocaleContextHolder.getLocale()));
                    errorList.add(cic);
                }
            }
            if (inclusionaccDetailsDto.getAccountName() != null) {
                boolean isArab = isArabic(inclusionaccDetailsDto.getAccountName());
                if (!isArab) {
                    String pattern = Constants.ALPHA_NUMER_SPACES;

                    boolean matches = Pattern.matches(pattern, inclusionaccDetailsDto.getAccountName());
                    if (!matches) {
                        error = true;
                        cic = new JSONObject();
                        cic.put("accountName", messageSource.getMessage("Pattern.accountInclusionDetailsDto.accountName", null, LocaleContextHolder.getLocale()));
                        errorList.add(cic);
                    } else if (inclusionaccDetailsDto.getAccountName().length() > 150) {
                        error = true;
                        cic = new JSONObject();
                        cic.put("accountName", messageSource.getMessage("escrowProject.va.VAL0051", null, LocaleContextHolder.getLocale()));
                        errorList.add(cic);
                    }
                }
            }

            if (error) {
                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0050", null, LocaleContextHolder.getLocale()), errorList);
            }

            ESCProject projectObj = escrowProjectRepository.getProject(inclusionaccDetailsDto.getProjectId());
            Integer projectType = projectObj.getProjectType();
            if (projectType == 0 && !inclusionaccDetailsDto.getBucketFlag().contains("P") || inclusionaccDetailsDto.getBucketFlag().contains("V")) {

                Client client = clientRepository.getCustomerDetails(projectObj.getClientId());

                long lastSeq = 99998;
                if (client.getInclSeq() != null) lastSeq = client.getInclSeq() - 1;

                client.setInclSeq(lastSeq);

                String remitter = "";   //FIX
                if (remitter == null) {

//                    Scheme scheme = schemeRepository.getScheme(projectObj.getClientId(), projectObj.getSchemeId());
//                    IBANGenerationRq request = new IBANGenerationRq();
//                    request.setProjectId(scheme.getId().getSchemeId());
//                    request.setUnitId(String.valueOf(lastSeq).toUpperCase());
//                    log.info("Inclusion request : :::::::" + request);
//                    IBANGenerationRs response = IBANGenerationServiceImpl.generateIban(request);
//                    if (response != null && response.getStatus().equals("SUCCESS")) {
//                        soapResponse = "SUCCESS";
//                        IBAN = response.getIbanNumber();
//                        BBAN = response.getAccountNumber();
//                        inclusionaccDetailsDto.setIbanAccountNumber(IBAN);
//                        inclusionaccDetailsDto.setBbanAccountNumber(BBAN);
//                    } else {
//                        log.error("Iban Generation Failed");
//                        soapResponse = "FAILED";
//                    }
                    if (soapResponse.equals("SUCCESS")) {
                        inclusionDetails = new AccountInclusionDetails();

                        clientRepository.save(client);

                        BeanUtils.copyProperties(inclusionaccDetailsDto, inclusionDetails);
                        inclusionDetails.setFundType(inclusionaccDetailsDto.getFundType());
                        inclusionDetails.setDstType(inclusionaccDetailsDto.getDstType());
                        AccountInclusionDetailsPK accountInclusionPK = new AccountInclusionDetailsPK(inclusionaccDetailsDto.getProjectId(), inclusionaccDetailsDto.getIbanAccountNumber());
                        inclusionDetails.setId(accountInclusionPK);
                        inclusionDetails.setBukFl("V");
                        saveStatus = accountInclusionDetailsRepo.save(inclusionDetails) != null ? true : false;
                        if (saveStatus) {

                            audit.creatAudit(null, getJsonObject(inclusionDetails).toJSONString(), AuditFuctions.CREATE, Constants.SCREEN_ID.PROJECT_PROFILE, "NIL", Constants.MESSAGE_STATUS.PROCESSED, messageSource.getMessage("escrowProject.va.VAL0012", null, LocaleContextHolder.getLocale()), principal);

                            return new ServiceResponse(Constants.MESSAGE_STATUS.SUCCESS, messageSource.getMessage("escrowProject.va.VAL0012", null, LocaleContextHolder.getLocale()), null);

                        } else {

                            return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0010", null, LocaleContextHolder.getLocale()), null);
                        }

                    } else {

                        return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0010", null, LocaleContextHolder.getLocale()), null);
                    }
                } else {

                    return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0011", null, LocaleContextHolder.getLocale()), null);
                }

            } else {
                inclusionDetails = accountInclusionDetailsRepo.checkInclusionAccountDefined(inclusionaccDetailsDto.getProjectId(), inclusionaccDetailsDto.getIbanAccountNumber());

                if (!StringUtils.isEmpty(inclusionDetails)) {

                    return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0011", null, LocaleContextHolder.getLocale()), null);
                } else {
                    if (checkInclusionAccountIsAlreadyUsed(inclusionaccDetailsDto)) {
                        return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0011", null, LocaleContextHolder.getLocale()), null);
                    }
                    inclusionDetails = new AccountInclusionDetails();
                    BeanUtils.copyProperties(inclusionaccDetailsDto, inclusionDetails);
                    inclusionDetails.setFundType(inclusionaccDetailsDto.getFundType());
                    inclusionDetails.setDstType(inclusionaccDetailsDto.getDstType());
                    AccountInclusionDetailsPK aacPk = new AccountInclusionDetailsPK(inclusionaccDetailsDto.getProjectId(), inclusionaccDetailsDto.getIbanAccountNumber());
                    inclusionDetails.setId(aacPk);
                    inclusionDetails.setBukFl("P");
                    saveStatus = accountInclusionDetailsRepo.save(inclusionDetails) != null ? true : false;
                    if (saveStatus) {

                        audit.creatAudit(null, getJsonObject(inclusionDetails).toJSONString(), AuditFuctions.CREATE, Constants.SCREEN_ID.PROJECT_PROFILE, "NIL", Constants.MESSAGE_STATUS.PROCESSED, messageSource.getMessage("escrowProject.va.VAL0009", null, LocaleContextHolder.getLocale()), principal);
                        return new ServiceResponse(Constants.MESSAGE_STATUS.SUCCESS, messageSource.getMessage("escrowProject.va.VAL0009", null, LocaleContextHolder.getLocale()), null);
                    } else {

                        return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0010", null, LocaleContextHolder.getLocale()), null);
                    }

                }

            }

        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0010", null, LocaleContextHolder.getLocale()), null);
        }

    }
    private boolean checkInclusionAccountIsAlreadyUsed(AccountInclusionDetailsDto inclusionaccDetailsDto) {

        boolean flag = false;
        try {
            ESCProject project = escrowProjectRepository.getProject(inclusionaccDetailsDto.getProjectId());
            if (project.getEscrowAccount().equals(inclusionaccDetailsDto.getIbanAccountNumber())) {
                flag = true;
            } else if (project.getConsSubAccount().equals(inclusionaccDetailsDto.getIbanAccountNumber())) {
                flag = true;
            } else if (project.getMarketSubAccount().equals(inclusionaccDetailsDto.getIbanAccountNumber())) {
                flag = true;
            } else if (project.getRetnSubAccount().equals(inclusionaccDetailsDto.getIbanAccountNumber())) {
                flag = true;
            }

            AccountInclusionDetails accountDet = accountInclusionDetailsRepo.checkInclusionAccountDefined(inclusionaccDetailsDto.getProjectId(), inclusionaccDetailsDto.getIbanAccountNumber());
            if (null != accountDet && !StringUtils.isEmpty(accountDet)) {
                flag = true;
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return flag;
    }

    @Override
    public ServiceResponse createAccountValidate(@Valid EscrowAccountDetailsDto escrowAccountDetailsDto, Principal principle) {
        try {
            List<JSONObject> errorList = new ArrayList<>();
            JSONObject cif = null;
            boolean error = false;

            if (escrowAccountDetailsDto.getEscrowIbanAccount() == null
                    || escrowAccountDetailsDto.getEscrowIbanAccount().equals("")) {
                error = true;
                cif = new JSONObject();
                cif.put("escrowIbanAccount", messageSource.getMessage("NotEmpty.ecrowAccountDetailsDto.escAccount",
                        null, LocaleContextHolder.getLocale()));
                errorList.add(cif);
            }
            if (escrowAccountDetailsDto.getEscrowAccountName() == null
                    || escrowAccountDetailsDto.getEscrowAccountName().equals("")) {
                error = true;
                cif = new JSONObject();
                cif.put("escrowAccountName", messageSource.getMessage("NotEmpty.ecrowAccountDetailsDto.escAccountName",
                        null, LocaleContextHolder.getLocale()));
                errorList.add(cif);
            }
            if (escrowAccountDetailsDto.getBucketFlag().equalsIgnoreCase("P")
                    && (escrowAccountDetailsDto.getConsSubAccount() == null
                    || escrowAccountDetailsDto.getConsSubAccount().equals(""))) {
                error = true;
                cif = new JSONObject();
                cif.put("consSubAccount", messageSource.getMessage("NotEmpty.ecrowAccountDetailsDto.consSubAccount",
                        null, LocaleContextHolder.getLocale()));
                errorList.add(cif);
            }

            if (escrowAccountDetailsDto.getBucketFlag().equalsIgnoreCase("P")
                    && (escrowAccountDetailsDto.getMarSubAccount() == null
                    || escrowAccountDetailsDto.getMarSubAccount().equals(""))) {
                error = true;
                cif = new JSONObject();
                cif.put("marSubAccount", messageSource.getMessage("NotEmpty.ecrowAccountDetailsDto.marSubAccount", null,
                        LocaleContextHolder.getLocale()));
                errorList.add(cif);
            }

            if (escrowAccountDetailsDto.getBucketFlag().equalsIgnoreCase("P")
                    && (escrowAccountDetailsDto.getRetSubAccount() == null
                    || escrowAccountDetailsDto.getRetSubAccount().equals(""))) {
                error = true;
                cif = new JSONObject();
                cif.put("retSubAccount", messageSource.getMessage("NotEmpty.ecrowAccountDetailsDto.retSubAccount", null,
                        LocaleContextHolder.getLocale()));
                errorList.add(cif);
            }

            /*--------------------------same account check--------------------*/

            if (!error && escrowAccountDetailsDto.getBucketFlag().equalsIgnoreCase("P")) {
                if (escrowAccountDetailsDto.getEscrowIbanAccount()
                        .equals(escrowAccountDetailsDto.getConsSubAccount())) {
                    error = true;
                    cif = new JSONObject();
                    cif.put("escrowIbanAccount", messageSource.getMessage("NotEmpty.ecrowAccountDetailsDto.sameAccount",
                            null, LocaleContextHolder.getLocale()));
                    errorList.add(cif);
                    cif = new JSONObject();
                    cif.put("consSubAccount", messageSource.getMessage("NotEmpty.ecrowAccountDetailsDto.sameAccount",
                            null, LocaleContextHolder.getLocale()));
                    errorList.add(cif);
                }

                if (escrowAccountDetailsDto.getEscrowIbanAccount().equals(escrowAccountDetailsDto.getMarSubAccount())) {
                    error = true;
                    cif = new JSONObject();
                    cif.put("escrowIbanAccount", messageSource.getMessage("NotEmpty.ecrowAccountDetailsDto.sameAccount",
                            null, LocaleContextHolder.getLocale()));
                    errorList.add(cif);
                    cif = new JSONObject();
                    cif.put("marSubAccount", messageSource.getMessage("NotEmpty.ecrowAccountDetailsDto.sameAccount",
                            null, LocaleContextHolder.getLocale()));
                    errorList.add(cif);
                }

                if (escrowAccountDetailsDto.getEscrowIbanAccount().equals(escrowAccountDetailsDto.getRetSubAccount())) {
                    error = true;
                    cif = new JSONObject();
                    cif.put("escrowIbanAccount", messageSource.getMessage("NotEmpty.ecrowAccountDetailsDto.sameAccount",
                            null, LocaleContextHolder.getLocale()));
                    errorList.add(cif);
                    cif = new JSONObject();
                    cif.put("retSubAccount", messageSource.getMessage("NotEmpty.ecrowAccountDetailsDto.sameAccount",
                            null, LocaleContextHolder.getLocale()));
                    errorList.add(cif);
                }

                if (escrowAccountDetailsDto.getConsSubAccount().equals(escrowAccountDetailsDto.getMarSubAccount())) {
                    error = true;
                    cif = new JSONObject();
                    cif.put("consSubAccount", messageSource.getMessage("NotEmpty.ecrowAccountDetailsDto.sameAccount",
                            null, LocaleContextHolder.getLocale()));
                    errorList.add(cif);
                    cif = new JSONObject();
                    cif.put("marSubAccount", messageSource.getMessage("NotEmpty.ecrowAccountDetailsDto.sameAccount",
                            null, LocaleContextHolder.getLocale()));
                    errorList.add(cif);
                }

                if (escrowAccountDetailsDto.getConsSubAccount().equals(escrowAccountDetailsDto.getRetSubAccount())) {
                    error = true;
                    cif = new JSONObject();
                    cif.put("consSubAccount", messageSource.getMessage("NotEmpty.ecrowAccountDetailsDto.sameAccount",
                            null, LocaleContextHolder.getLocale()));
                    errorList.add(cif);
                    cif = new JSONObject();
                    cif.put("retSubAccount", messageSource.getMessage("NotEmpty.ecrowAccountDetailsDto.sameAccount",
                            null, LocaleContextHolder.getLocale()));
                    errorList.add(cif);
                }

                if (escrowAccountDetailsDto.getMarSubAccount().equals(escrowAccountDetailsDto.getRetSubAccount())) {
                    error = true;
                    cif = new JSONObject();
                    cif.put("marSubAccount", messageSource.getMessage("NotEmpty.ecrowAccountDetailsDto.sameAccount",
                            null, LocaleContextHolder.getLocale()));
                    errorList.add(cif);
                    cif = new JSONObject();
                    cif.put("retSubAccount", messageSource.getMessage("NotEmpty.ecrowAccountDetailsDto.sameAccount",
                            null, LocaleContextHolder.getLocale()));
                    errorList.add(cif);
                }
            }

            /*--------------------------same account check--------------------*/

            if (error) {
                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED,
                        messageSource.getMessage("escrowProject.va.VAL0050", null, LocaleContextHolder.getLocale()),
                        errorList);
            } else {

                return new ServiceResponse(Constants.MESSAGE_STATUS.SUCCESS, messageSource.getMessage("escrowProject.va.VAL0047", null, LocaleContextHolder.getLocale()), null);
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return new ServiceResponse(Constants.ERROR_CODE, messageSource.getMessage("escrowProject.va.VAL0050", null, LocaleContextHolder.getLocale()), null);
        }

    }


    @Override
    public ServiceResponse saveEscrowAccountDetails(@Valid EscrowAccountDetailsDto escrowAccountDetailsDto, Principal principal) {
        ESCProject saveStatus = null;
        EscrowAccountDetails escrowAccountDetails = new EscrowAccountDetails();
        String nextGroupId = "";
        try {
            ESCProject newProjectObj = escrowProjectRepository.getProject(escrowAccountDetailsDto.getProjectId());
            if (StringUtils.isEmpty(newProjectObj)) {

                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0017", null, LocaleContextHolder.getLocale()), null);
            }
            else {
                escrowAccountDetails = new EscrowAccountDetails();

                newProjectObj.setEscrowAccount(escrowAccountDetailsDto.getEscrowIbanAccount());
                newProjectObj.setBranchAccountNumber(escrowAccountDetailsDto.getCustomerIbanAccount());
                newProjectObj.setStatus("PROCESSD");
                newProjectObj.setDistFlowFlag(escrowAccountDetailsDto.getDistFlowFlag());
                escrowAccountDetails.setProjectId(escrowAccountDetailsDto.getProjectId());
                BeanUtils.copyProperties(escrowAccountDetailsDto, escrowAccountDetails);

                escrowAccountDetailsRepository.save(escrowAccountDetails);
                saveStatus = escrowProjectRepository.save(newProjectObj);

                if (!StringUtils.isEmpty(saveStatus)) {

                    audit.creatAudit(null, getJsonObject(escrowAccountDetails).toJSONString(), AuditFuctions.CREATE, Constants.SCREEN_ID.PROJECT_PROFILE, "NIL", Constants.MESSAGE_STATUS.PROCESSED, messageSource.getMessage("escrowProject.va.VAL0015", null, LocaleContextHolder.getLocale()), principal);

                    return new ServiceResponse(Constants.MESSAGE_STATUS.SUCCESS, messageSource.getMessage("escrowProject.va.VAL0015", null, LocaleContextHolder.getLocale()), null);

                } else {

                    return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0014", null, LocaleContextHolder.getLocale()), null);

                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);

            return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0013", null, LocaleContextHolder.getLocale()), null);
        }

    }

    private JSONObject getJsonObject(EscrowAccountDetails escrowAccountDetails) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            String jsonString = mapper.writeValueAsString(escrowAccountDetails);
            JSONParser parser = new JSONParser();
            JSONObject json = (JSONObject) parser.parse(jsonString);
            return json;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return new JSONObject();
    }

    @Override
    public Object getProjectAndAccountDetails(String projectId, Principal principal) {
        ESCProject newProjectObj = null;
        ESCProjectDto projectDto = null;
        EscrowAccountDetails escrowAccountDetails = null;
        Client client = null;
        SimpleDateFormat dateFormat = null;
        try {
            dateFormat = new SimpleDateFormat("dd-MM-yyyy");
            projectDto = new ESCProjectDto();
            newProjectObj = escrowProjectRepository.getProject(projectId);
            escrowAccountDetails = escrowAccountDetailsRepository.getEscrowAccountDetails(projectId);
            client = clientRepository.getCustomerDetails(newProjectObj.getClientId());
            BeanUtils.copyProperties(newProjectObj, projectDto);
            projectDto.setCustCicNo(client.getCustomerBaseNumber());
            if (null != newProjectObj.getProjStartDate()) {

                projectDto.setStrStartDate(dateFormat.format(newProjectObj.getProjStartDate()));
            }

            if (newProjectObj.getRegaLicenseExpDate() != null) {
                projectDto.setStrRegaLicenceExpDate(dateFormat.format(newProjectObj.getRegaLicenseExpDate()));
            }
            projectDto.setCommisionLevel(client.getCommisionLevel());
            if (projectDto.getCommisionLevel() == 1) {
                if (newProjectObj.getProfileId() != null) {
                    projectDto.setProfileId(newProjectObj.getProfileId());
                }
                if (newProjectObj.getProfileExpiryDate() != null) {

                    projectDto.setProfileExpiryDate(dateFormat.format(newProjectObj.getProfileExpiryDate()));
                }
                if (newProjectObj.getSecProfileId() != null) {
                    projectDto.setSecProfileId(newProjectObj.getSecProfileId());
                }
            } else {

                projectDto.setProfileExpiryDate(null);
                projectDto.setSecProfileId(null);
            }

            if (newProjectObj.getEndDate() != null)
                projectDto.setStrEndDate(dateFormat.format(newProjectObj.getEndDate()));

            projectDto.setClientName(client.getClientName());
            if (!StringUtils.isEmpty(escrowAccountDetails)) {
                projectDto.setEscrowIbanAccount(newProjectObj.getEscrowAccount());
                projectDto.setEscrowBbanAccount(escrowAccountDetails.getEscrowBbanAccount());
                projectDto.setCustomerBbanAccount(escrowAccountDetails.getCustomerBbanAccount());

                projectDto.setConsSubAccount(escrowAccountDetails.getConsSubAccount());
                projectDto.setConstructionSource(escrowAccountDetails.getConstructionSource());
                projectDto.setConsPercenatge(escrowAccountDetails.getConsPercenatge());

                projectDto.setMarkettingSource(escrowAccountDetails.getMarkettingSource());
                projectDto.setMarSubAccount(escrowAccountDetails.getMarSubAccount());
                projectDto.setMarPercenatge(escrowAccountDetails.getMarPercenatge());

                projectDto.setRetensionSource(escrowAccountDetails.getRetensionSource());
                projectDto.setRetSubAccount(escrowAccountDetails.getRetSubAccount());
                projectDto.setRetPercenatge(escrowAccountDetails.getRetPercenatge());

                projectDto.setCustomerAccountName(escrowAccountDetails.getCustomerAccountName());
                projectDto.setEscrowAccountName(escrowAccountDetails.getEscrowAccountName());

                projectDto.setEscrowAccountCur(escrowAccountDetails.getEscrowAccountCurrency());

                projectDto.setStpRule(newProjectObj.getStpRule());
                projectDto.setAdvanceCancelRule(newProjectObj.getAdvanceCancelRule());

                projectDto.setBucketFlag(escrowAccountDetails.getBucketFlag());

            } else {
                projectDto.setConstructionSource(1);
                projectDto.setMarkettingSource(1);
                projectDto.setRetensionSource(2);
            }
            projectDto.setProjectCif(newProjectObj.getProjectCif());
            if (newProjectObj.getStatus().equals("REJECT") || newProjectObj.getStatus().equals("REJECTD")) {
                projectDto.setRejectReason(newProjectObj.getRejectReason());
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);

        }
        return projectDto;
    }

    @Override
    public ServiceResponse returnTaskDetails(String projectId, String reason, Principal principal) {

        ESCProject saveStatus = null;
        ESCProject projectObj = null;
        try {

            projectObj = escrowProjectRepository.getProject(projectId);

            if (StringUtils.isEmpty(projectObj)) {
                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0064", null, LocaleContextHolder.getLocale()), null);

            } else if (StringUtils.isEmpty(projectObj.getEscrowAccount())) {
                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0065", null, LocaleContextHolder.getLocale()), null);

            }
            else if (StringUtils.isEmpty(projectObj.getStatus().equalsIgnoreCase(Constants.MESSAGE_STATUS.RETURN))) {
                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0079", null, LocaleContextHolder.getLocale()), null);
            } else {
                projectObj.setStatus(Constants.MESSAGE_STATUS.RETURN);
                if (StringUtils.isEmpty(reason)) {
                    reason = "";
                }
                projectObj.setRejectReason(reason);
                projectObj.setCreatedId(principal.getName());
                projectObj.setDateverified(new Date());
                saveStatus = escrowProjectRepository.save(projectObj);
                if (!StringUtils.isEmpty(saveStatus)) {
                    return new ServiceResponse(Constants.MESSAGE_STATUS.SUCCESS, messageSource.getMessage("escrowProject.va.VAL0084", null, LocaleContextHolder.getLocale()), null);

                } else {
                    return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0085", null, LocaleContextHolder.getLocale()), null);
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0085", null, LocaleContextHolder.getLocale()), null);
        }

    }
    @Override
    public ServiceResponse rejectTaskDetails(String projectId, String reason, Principal principal) {

        ESCProject saveStatus = null;
        ESCProject projectObj = null;
        try {

            projectObj = escrowProjectRepository.getProject(projectId);

            if (StringUtils.isEmpty(projectObj)) {
                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0064", null, LocaleContextHolder.getLocale()), null);

            } else if (StringUtils.isEmpty(projectObj.getEscrowAccount())) {
                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0065", null, LocaleContextHolder.getLocale()), null);

            }
            else {
                if (StringUtils.isEmpty(reason)) {
                    reason = "";
                }
                projectObj.setStatus("REJECT");
                projectObj.setRejectReason(reason);
                projectObj.setCreatedId(principal.getName());
                projectObj.setDateverified(new Date());
                saveStatus = escrowProjectRepository.save(projectObj);
                if (!StringUtils.isEmpty(saveStatus)) {
                    return new ServiceResponse(Constants.MESSAGE_STATUS.SUCCESS, messageSource.getMessage("escrowProject.va.VAL0067", null, LocaleContextHolder.getLocale()), null);

                } else {
                    return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0068", null, LocaleContextHolder.getLocale()), null);

                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0068", null, LocaleContextHolder.getLocale()), null);
        }

    }

    @Override
    public ServiceResponse approvetaskDetails(String projectId, Principal principal) {

        boolean saveStatus = false;
        ESCProject projectObj = null;
        FinacialAccountDetails finacialAccountDetails = null;
        String statusMessage = "";
        EscrowAccountDetails escAccounts = new EscrowAccountDetails();
        try {

            projectObj = escrowProjectRepository.getProject(projectId);
            log.info("Project fetched: {}", projectObj);
            log.info("clientId: {}", projectObj.getClientId());
            String oldData = getJsonObject(projectObj).toJSONString();
            Client client = clientRepository.getCustomerDetails(projectObj.getClientId());

            if (StringUtils.isEmpty(projectObj)) {
                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0064", null, LocaleContextHolder.getLocale()), null);

            } else if (StringUtils.isEmpty(projectObj.getEscrowAccount()) && !projectObj.getStatus().equals("INIDEF")) {
                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0065", null, LocaleContextHolder.getLocale()), null);

            }
            else if (projectObj.getCreatedId().equals(principal.getName())) {
                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0072", null, LocaleContextHolder.getLocale()), null);
            } else if (projectObj.getStatus().equals("INIDEF")) {
                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0063", null, LocaleContextHolder.getLocale()), null);
            } else {

                if (projectObj.getStatus().equals("PROCESSD")) {

                    if (!checkMandatoryFilesUploaded(projectId)) {
                        return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL00100", null, LocaleContextHolder.getLocale()), null);
                    }

                    escAccounts = escrowAccountDetailsRepository.getEscrowAccountDetails(projectId);

                    if (("V").equalsIgnoreCase(escAccounts.getBucketFlag()) && null != escAccounts.getEscrowIbanAccount() && null == projectObj.getConsSubAccount() || "".equals(projectObj.getConsSubAccount())) {
                        String acc = escAccounts.getEscrowIbanAccount();
                        String consAccNum = customerProductService.createAccount(projectObj, acc, "A", "NIL");
                        if (("NIL").equalsIgnoreCase(consAccNum)) {
                            return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0087", null, LocaleContextHolder.getLocale()), null);
                        } else {
                            String vaDeactivate = customerProductService.createAccount(projectObj, acc, "S", consAccNum);
                            if ("NIL".equalsIgnoreCase(vaDeactivate)){
                                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL00104", null, LocaleContextHolder.getLocale()), null);
                            } else {
                                log.info("Constuction account number Deactivated Successfully ");
                            }
                            projectObj.setConsSubAccount(consAccNum);
                            ESCProject statusCons = escrowProjectRepository.save(projectObj);
                            if (!StringUtils.isEmpty(statusCons)) {
                                escAccounts.setConsSubAccount(consAccNum);
                                escrowAccountDetailsRepository.save(escAccounts);
                                log.info("Successfully constuction account number");
                            } else {
                                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0087", null, LocaleContextHolder.getLocale()), null);
                            }
                        }
                    }

                    if (("V").equalsIgnoreCase(escAccounts.getBucketFlag()) && null != escAccounts.getEscrowIbanAccount() &&  null == projectObj.getMarketSubAccount() || "".equals(projectObj.getMarketSubAccount())) {
                        String acc = escAccounts.getEscrowIbanAccount();
                        String markAccNum = customerProductService.createAccount(projectObj, acc, "A", "NIL");
                        if (markAccNum.equals("NIL")) {
                            return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0088", null, LocaleContextHolder.getLocale()), null);
                        } else {
                            String vaDeactivate = customerProductService.createAccount(projectObj, acc, "S", markAccNum);
                            if ("NIL".equalsIgnoreCase(vaDeactivate)){
                                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL00105", null, LocaleContextHolder.getLocale()), null);
                            } else {
                                log.info("Marketing account number Deactivated Successfully ");
                            }
                            projectObj.setMarketSubAccount(markAccNum);
                            escrowProjectRepository.save(projectObj);
                            escAccounts.setMarSubAccount(markAccNum);
                            escrowAccountDetailsRepository.save(escAccounts);
                        }
                    }

                    if (("V").equalsIgnoreCase(escAccounts.getBucketFlag()) && null == projectObj.getRetnSubAccount() || "".equals(projectObj.getRetnSubAccount())) {
                        String acc = escAccounts.getEscrowIbanAccount();
                        String retAccNum = customerProductService.createAccount(projectObj, acc, "A", "NIL");
                        if (retAccNum.equals("NIL")) {
                            return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0089", null, LocaleContextHolder.getLocale()), null);
                        } else {
                            String vaDeactivate = customerProductService.createAccount(projectObj, acc, "S", retAccNum);
                            if ("NIL".equalsIgnoreCase(vaDeactivate)){
                                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL00106", null, LocaleContextHolder.getLocale()), null);
                            } else {
                                log.info("Retention account number Deactivated Successfully ");
                            }
                            projectObj.setRetnSubAccount(retAccNum);
                            escrowProjectRepository.save(projectObj);
                            escAccounts.setRetSubAccount(retAccNum);
                            escrowAccountDetailsRepository.save(escAccounts);
                        }
                    }

                    projectObj.setStatus("ACTIVED");
                    projectObj.setDistStatus(1);
                    statusMessage = "escrowProject.va.VAL0062";
                    Integer projectType = projectObj.getProjectType();
                    if (projectType == 0) {
                        List<String> inclusionlist = accountInclusionDetailsRepo.getAllInclusionAccountForProject(projectId);
                        EscrowAccountDetails escrowAccount = escrowAccountDetailsRepository.getEscrowAccountDetails(projectId);
                        String escrowBban = escrowAccount.getEscrowBbanAccount();

                        escrowAccount.setConsSubAccount(projectObj.getConsSubAccount());
                        escrowAccount.setMarSubAccount(projectObj.getMarketSubAccount());
                        escrowAccount.setRetSubAccount(projectObj.getRetnSubAccount());

                        for (String ibanaccountNumber : inclusionlist) {
                            VirtualAccountDefinition accountDefinition = virtualAccountDefinitionRepository.validateAccountByIban(ibanaccountNumber);
                            accountDefinition.setStatus("VERIFIED");
                            accountDefinition.setDateVer(new Date());
                            accountDefinition.setCollectionAcc(escrowBban);
                            accountDefinition.setVerId(principal.getName());
                            virtualAccountDefinitionRepository.save(accountDefinition);
                        }
                    }
                } else if (projectObj.getStatus().equals("REJECT")) {
                    projectObj.setStatus("REJECTD");
                    statusMessage = "escrowProject.va.VAL0076";
                } else if (projectObj.getStatus().equals("PRECLOSE")) {
                    projectObj.setStatus("PRECLOSD");
                    projectObj.setClosureStatus("PRECLOSE");
                    statusMessage = "escrowProject.va.VAL0073";
                    projectObj.setClosureDate(new Date());
                } else if (projectObj.getStatus().equals("POSTCLOS")) {
                    statusMessage = "escrowProject.va.VAL0074";
                    projectObj.setStatus("POSTCLSD");
                    projectObj.setClosureDate(new Date());
                    projectObj.setClosureStatus("CLOSE");
                } else {
                    projectObj.setStatus("ACTIVED");
                    projectObj.setDistStatus(1);
                    statusMessage = "escrowProject.va.VAL0062";
                }
                projectObj.setVerifiedId(principal.getName());
                projectObj.setDateverified(new Date());
                saveStatus = escrowProjectRepository.save(projectObj) != null ? true : false;

                if (saveStatus) {
                    audit.creatAudit(oldData, getJsonObject(projectObj).toJSONString(), AuditFuctions.MODIFY, Constants.SCREEN_ID.PROJECT_PROFILE, "NIL", Constants.MESSAGE_STATUS.PROCESSED, messageSource.getMessage(statusMessage, null, LocaleContextHolder.getLocale()), principal);
                    return new ServiceResponse(Constants.MESSAGE_STATUS.SUCCESS, messageSource.getMessage(statusMessage, null, LocaleContextHolder.getLocale()), null);

                } else {
                    return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0063", null, LocaleContextHolder.getLocale()), null);

                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0063", null, LocaleContextHolder.getLocale()), null);
        }

    }

    private boolean checkMandatoryFilesUploaded(String projectId) {
        boolean flag = false;
        List<String> projectNameList = null;
        int present = 0;
        try {
            String apprLevel = "RED Escrow Account Opening";
            String requestId= "";
            projectNameList = projectDocumentDefinitionRepository.getMandatoryDocs(apprLevel);
            for (String documentName : projectNameList) {
                Integer docListNo = projectDocumentDetailsRepository.getProjectDocumentDetailsByPK(projectId,
                        documentName, apprLevel, requestId);
                if (null == docListNo || docListNo == 0) {
                    present++;
                }
            }
            if (present > 0) {
                flag = false;
            } else {
                flag = true;
            }

        }  catch (Exception e) {
            log.error(e.getMessage(), e);
            flag = false;
        }

        return flag;
    }

//    @Override
//    public ServiceResponse updateProjectDetails(@Valid ESCProjectDto projectDto, Principal principal) {
//        ESCProject projectObj = new ESCProject();
//        boolean saveStatus = false;
//        try {
//            if (!checkProjectCisAndProjectId(projectDto)) {
    ////                if (checkCisInPrj(projectDto.getProjectCif())) {
    ////                    return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0098", null, LocaleContextHolder.getLocale()), null);
    ////                } else
//                    if (checkCisExistInClient(projectDto.getProjectCif())) {
//                    return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0099", null, LocaleContextHolder.getLocale()), null);
//
//                }
//            }
//            BigDecimal bdblretension = calcalutePercentage(projectDto.getProjectValue(), Constants.ESCROW_PROJECT.CAP_RET_PER);
//            BigDecimal bdblmarketing = calcalutePercentage(projectDto.getProjectValue(), Constants.ESCROW_PROJECT.CAP_MAR_PER);
//            BigDecimal bdblconstruction = calcalutePercentage(projectDto.getProjectValue(), Constants.ESCROW_PROJECT.CAP_CON_PER);
//
//            projectDto.setProjectRetentionValue(bdblretension);
//            projectDto.setProjectConsValue(bdblconstruction);
//            projectDto.setProjectMarketingValue(bdblmarketing);
//
//            projectObj = escrowProjectRepository.getProject(projectDto.getProjectId());
//            if (StringUtils.isEmpty(projectObj)) {
//
//                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0017", null, LocaleContextHolder.getLocale()), null);
//            } else if (!isArabic(projectDto.getProjectNameArb())) {
//
//                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0007", null, LocaleContextHolder.getLocale()), null);
//            } else if (!isArabic(projectDto.getDeveloperName())) {
//
//                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0006", null, LocaleContextHolder.getLocale()), null);
//            } else if (projectObj.getStatus().equals("POSTCLOS") || projectObj.getStatus().equals("POSTCLSD") || projectObj.getStatus().equals("PRECLOSD")) {
//
//                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0075", null, LocaleContextHolder.getLocale()), null);
//            } else {
//                BeanUtils.copyProperties(projectDto, projectObj);
//                DateFormat formatProf = new SimpleDateFormat("dd-MM-yyyy");
//                if (projectDto.getProfileExpiryDate() != null) {
//                    projectObj.setProfileExpiryDate(formatProf.parse(projectDto.getProfileExpiryDate()));
//                }
//
//                String oldData = getJsonObject(projectObj).toJSONString();
//                projectObj.setStatus("PROCESSD");
//                projectObj.setLastSchmeDate(new Date());
//                projectObj.setRegaLicenseExpDate(stringToDateConverter(projectDto.getStrRegaLicenceExpDate()));
//                projectObj.setEndDate(stringToDateConverter(projectDto.getStrEndDate()));
//                projectObj.setProjStartDate(stringToDateConverter(projectDto.getStrStartDate()));
//                projectObj.setProjectName(projectDto.getProjectName());
//                projectObj.setProjectNameArb(projectDto.getProjectNameArb());
//                projectObj.setProjectSiteAddress1(projectDto.getProjectSiteAddress1());
//                projectObj.setProjectSiteAddress2(projectDto.getProjectSiteAddress2());
//                projectObj.setProjectSiteAddress3(projectDto.getProjectSiteAddress3());
//                projectObj.setProjectSiteAddress4(projectDto.getProjectSiteAddress4());
//                projectObj.setDeveloperName(projectDto.getDeveloperName());
//                projectObj.setDeveloperNameEnglish(projectDto.getDeveloperNameEnglish());
//                projectObj.setPosMerchantId(projectDto.getPosMerchantId());
//                projectObj.setCreatedId(principal.getName());
//                projectObj.setCommisionAccount(projectDto.getCommisionAccount());
//                projectObj.setClientId(projectDto.getClientId());
//
//                if (projectDto.getProjectType() != null) {
//                    projectObj.setProjectType(projectDto.getProjectType());
//                }
//
//                if (projectDto.getStpRule() != null) {
//                    projectObj.setStpRule(projectDto.getStpRule());
//                }
//                if (projectDto.getPosMerchantId() != null) {
//                    projectObj.setPosMerchantId(projectDto.getPosMerchantId());
//                }
//
//                if (projectDto.getPosInternalAccount() != null) {
//                    projectObj.setPosInternalAccount(projectDto.getPosInternalAccount());
//                } else {
//                    projectObj.setPosInternalAccount("");
//                }
//
//                if (projectDto.getPosChargeAccount() != null) {
//                    projectObj.setPosChargeAccount(projectDto.getPosChargeAccount());
//                } else {
//                    projectObj.setPosChargeAccount("");
//                }
//
//                if (projectObj.getProjectValue() != projectDto.getProjectValue()) {
//                    projectObj.setProjectValue(projectDto.getProjectValue());
//                    projectObj.setProjectConsValue(projectDto.getProjectConsValue());
//                    projectObj.setProjectMarketingValue(projectDto.getProjectMarketingValue());
//                    projectObj.setProjectRetentionValue(projectDto.getProjectRetentionValue());
//
//                }
//                projectObj.setTotalArea(projectDto.getTotalArea());
//
//                projectObj.setClientIbanCode(projectDto.getClientIbanCode());
//                projectObj.setNumberOfUnits(projectDto.getNumberOfUnits());
//                projectObj.setSchemeId(projectDto.getProjectId());
//
//                FinacialAccountDetails finacialAccountDetails = new FinacialAccountDetails();
//                BigDecimal zeroBigDecimal = BigDecimal.valueOf(0.00);
//
//                finacialAccountDetails.setConstructionAccount(zeroBigDecimal);
//                finacialAccountDetails.setCurrentConstructionAccount(zeroBigDecimal);
//                finacialAccountDetails.setCurrentMarkettingAccount(zeroBigDecimal);
//                finacialAccountDetails.setMarkettingAccount(zeroBigDecimal);
//                finacialAccountDetails.setCurrentRetentionAccount(zeroBigDecimal);
//                finacialAccountDetails.setRetentionAccount(zeroBigDecimal);
//                finacialAccountDetails.setSurplusAccount(zeroBigDecimal);
//                finacialAccountDetails.setCurrentSurplusAccount(zeroBigDecimal);
//                finacialAccountDetails.setProDate(new Date());
//                finacialAccountDetails.setProjectId(projectDto.getProjectId());
//                finacialAccountDetails.setCurrentLoanAmount(zeroBigDecimal);
//                finacialAccountDetails.setCurrentInvestmentAmount(zeroBigDecimal);
//                finacialAccountDetails.setInvestmentAmount(zeroBigDecimal);
//                finacialAccountDetails.setLoanAmount(zeroBigDecimal);
//                finacialAccountDetails.setBankFinanceAmount(zeroBigDecimal);
//                finacialAccountDetails.setCurrentBankFinanceAmount(zeroBigDecimal);
//                finacialAccountDetails.setBankHafizAmount(zeroBigDecimal);
//                finacialAccountDetails.setCurrentHafizAmount(zeroBigDecimal);
//                finacialAccountDetails.setBankPosAmount(zeroBigDecimal);
//                finacialAccountDetails.setCurrentPosAmount(zeroBigDecimal);
//                saveStatus = finacialAccountDetailsRepository.save(finacialAccountDetails) != null ? true : false;
//
//             ESCProject project =  escrowProjectRepository.save(projectObj);
//                if (project!=null) {
//
//                    audit.creatAudit(oldData, getJsonObject(projectObj).toJSONString(), AuditFuctions.MODIFY, Constants.SCREEN_ID.PROJECT_PROFILE, projectObj.getClientId(), Constants.MESSAGE_STATUS.PROCESSED, messageSource.getMessage("escrowProject.va.VAL0019", null, LocaleContextHolder.getLocale()), principal);
//
//                    return new ServiceResponse(Constants.MESSAGE_STATUS.SUCCESS, messageSource.getMessage("escrowProject.va.VAL0019", null, LocaleContextHolder.getLocale()), null);
//
//                } else {
//                    return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0018", null, LocaleContextHolder.getLocale()), null);
//
//                }
//            }
//        } catch (Exception e) {
//            log.error(e.getMessage(), e);
//            return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0018", null, LocaleContextHolder.getLocale()), null);
//
//        }
//
//    }

//@Override
//public ServiceResponse updateProjectDetails(@Valid ESCProjectDto projectDto, Principal principal) {
//    ESCProject projectObj = new ESCProject();
//    boolean saveStatus = false;
//    try {
//        if (!checkProjectCisAndProjectId(projectDto)) {
//            if (checkCisExistInClient(projectDto.getProjectCif())) {
//                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0099", null, LocaleContextHolder.getLocale()), null);
//            }
//        }
//
//        BigDecimal bdblretension = calcalutePercentage(projectDto.getProjectValue(), Constants.ESCROW_PROJECT.CAP_RET_PER);
//        BigDecimal bdblmarketing = calcalutePercentage(projectDto.getProjectValue(), Constants.ESCROW_PROJECT.CAP_MAR_PER);
//        BigDecimal bdblconstruction = calcalutePercentage(projectDto.getProjectValue(), Constants.ESCROW_PROJECT.CAP_CON_PER);
//
//        projectDto.setProjectRetentionValue(bdblretension);
//        projectDto.setProjectConsValue(bdblconstruction);
//        projectDto.setProjectMarketingValue(bdblmarketing);
//
//        projectObj = escrowProjectRepository.getProject(projectDto.getProjectId());
//
//        if (StringUtils.isEmpty(projectObj)) {
//            return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0017", null, LocaleContextHolder.getLocale()), null);
//        } else if (!isArabic(projectDto.getProjectNameArb())) {
//            return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0007", null, LocaleContextHolder.getLocale()), null);
//        } else if (!isArabic(projectDto.getDeveloperName())) {
//            return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0006", null, LocaleContextHolder.getLocale()), null);
//        } else if (projectObj.getStatus().equals("POSTCLOS") || projectObj.getStatus().equals("POSTCLSD") || projectObj.getStatus().equals("PRECLOSD")) {
//            return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0075", null, LocaleContextHolder.getLocale()), null);
//        } else {
//
//            ESCProjectAuditDto oldAuditDto = new ESCProjectAuditDto();
//            BeanUtils.copyProperties(projectObj, oldAuditDto);
//
//            oldAuditDto.setProjectCost(projectObj.getProjectValue() != null ? projectObj.getProjectValue().toString() : null);
//            oldAuditDto.setDistributionStatus(projectObj.getDistStatus() != null ? projectObj.getDistStatus().toString() : null);
//            oldAuditDto.setCreatedBy(projectObj.getCreatedId());
//            oldAuditDto.setCreatedDate(projectObj.getDateCreated() != null ? projectObj.getDateCreated().toString() : null);
//            oldAuditDto.setModifiedBy(projectObj.getVerifiedId());
//            oldAuditDto.setModifiedDate(projectObj.getDateverified() != null ? projectObj.getDateverified().toString() : null);
//
//            Client client = clientRepository.getCustomerDetails(projectObj.getClientId());
//            oldAuditDto.setClientName(client != null ? client.getClientName() : null);
//
//            JsonObject oldEntityAudit = new JsonParser()
//                    .parse(getJsonObject(oldAuditDto).toJSONString())
//                    .getAsJsonObject();
//
//            BeanUtils.copyProperties(projectDto, projectObj);
//
//            DateFormat formatProf = new SimpleDateFormat("dd-MM-yyyy");
//            if (projectDto.getProfileExpiryDate() != null) {
//                projectObj.setProfileExpiryDate(formatProf.parse(projectDto.getProfileExpiryDate()));
//            }
//
//            projectObj.setStatus("PROCESSD");
//            projectObj.setLastSchmeDate(new Date());
//            projectObj.setRegaLicenseExpDate(stringToDateConverter(projectDto.getStrRegaLicenceExpDate()));
//            projectObj.setEndDate(stringToDateConverter(projectDto.getStrEndDate()));
//            projectObj.setProjStartDate(stringToDateConverter(projectDto.getStrStartDate()));
//            projectObj.setProjectName(projectDto.getProjectName());
//            projectObj.setProjectNameArb(projectDto.getProjectNameArb());
//            projectObj.setProjectSiteAddress1(projectDto.getProjectSiteAddress1());
//            projectObj.setProjectSiteAddress2(projectDto.getProjectSiteAddress2());
//            projectObj.setProjectSiteAddress3(projectDto.getProjectSiteAddress3());
//            projectObj.setProjectSiteAddress4(projectDto.getProjectSiteAddress4());
//            projectObj.setDeveloperName(projectDto.getDeveloperName());
//            projectObj.setDeveloperNameEnglish(projectDto.getDeveloperNameEnglish());
//            projectObj.setPosMerchantId(projectDto.getPosMerchantId());
//            projectObj.setCreatedId(principal.getName());
//            projectObj.setCommisionAccount(projectDto.getCommisionAccount());
//            projectObj.setClientId(projectDto.getClientId());
//
//            if (projectDto.getProjectType() != null) {
//                projectObj.setProjectType(projectDto.getProjectType());
//            }
//
//            if (projectDto.getStpRule() != null) {
//                projectObj.setStpRule(projectDto.getStpRule());
//            }
//
//            if (projectDto.getPosInternalAccount() != null) {
//                projectObj.setPosInternalAccount(projectDto.getPosInternalAccount());
//            } else {
//                projectObj.setPosInternalAccount("");
//            }
//
//            if (projectDto.getPosChargeAccount() != null) {
//                projectObj.setPosChargeAccount(projectDto.getPosChargeAccount());
//            } else {
//                projectObj.setPosChargeAccount("");
//            }
//
//            if (projectObj.getProjectValue() != projectDto.getProjectValue()) {
//                projectObj.setProjectValue(projectDto.getProjectValue());
//                projectObj.setProjectConsValue(projectDto.getProjectConsValue());
//                projectObj.setProjectMarketingValue(projectDto.getProjectMarketingValue());
//                projectObj.setProjectRetentionValue(projectDto.getProjectRetentionValue());
//            }
//
//            projectObj.setTotalArea(projectDto.getTotalArea());
//            projectObj.setClientIbanCode(projectDto.getClientIbanCode());
//            projectObj.setNumberOfUnits(projectDto.getNumberOfUnits());
//            projectObj.setSchemeId(projectDto.getProjectId());
//
//            FinacialAccountDetails finacialAccountDetails = new FinacialAccountDetails();
//            BigDecimal zeroBigDecimal = BigDecimal.valueOf(0.00);
//
//            finacialAccountDetails.setConstructionAccount(zeroBigDecimal);
//            finacialAccountDetails.setCurrentConstructionAccount(zeroBigDecimal);
//            finacialAccountDetails.setCurrentMarkettingAccount(zeroBigDecimal);
//            finacialAccountDetails.setMarkettingAccount(zeroBigDecimal);
//            finacialAccountDetails.setCurrentRetentionAccount(zeroBigDecimal);
//            finacialAccountDetails.setRetentionAccount(zeroBigDecimal);
//            finacialAccountDetails.setSurplusAccount(zeroBigDecimal);
//            finacialAccountDetails.setCurrentSurplusAccount(zeroBigDecimal);
//            finacialAccountDetails.setProDate(new Date());
//            finacialAccountDetails.setProjectId(projectDto.getProjectId());
//            finacialAccountDetails.setCurrentLoanAmount(zeroBigDecimal);
//            finacialAccountDetails.setCurrentInvestmentAmount(zeroBigDecimal);
//            finacialAccountDetails.setInvestmentAmount(zeroBigDecimal);
//            finacialAccountDetails.setLoanAmount(zeroBigDecimal);
//            finacialAccountDetails.setBankFinanceAmount(zeroBigDecimal);
//            finacialAccountDetails.setCurrentBankFinanceAmount(zeroBigDecimal);
//            finacialAccountDetails.setBankHafizAmount(zeroBigDecimal);
//            finacialAccountDetails.setCurrentHafizAmount(zeroBigDecimal);
//            finacialAccountDetails.setBankPosAmount(zeroBigDecimal);
//            finacialAccountDetails.setCurrentPosAmount(zeroBigDecimal);
//
//            saveStatus = finacialAccountDetailsRepository.save(finacialAccountDetails) != null ? true : false;
//
//            ESCProject savedProject = escrowProjectRepository.save(projectObj);
//
//            if (savedProject != null) {
//
//                ESCProjectAuditDto newAuditDto = new ESCProjectAuditDto();
//                BeanUtils.copyProperties(savedProject, newAuditDto);
//
//                newAuditDto.setProjectCost(savedProject.getProjectValue() != null ? savedProject.getProjectValue().toString() : null);
//                newAuditDto.setDistributionStatus(savedProject.getDistStatus() != null ? savedProject.getDistStatus().toString() : null);
//                newAuditDto.setCreatedBy(savedProject.getCreatedId());
//                newAuditDto.setCreatedDate(savedProject.getDateCreated() != null ? savedProject.getDateCreated().toString() : null);
//                newAuditDto.setModifiedBy(savedProject.getVerifiedId());
//                newAuditDto.setModifiedDate(savedProject.getDateverified() != null ? savedProject.getDateverified().toString() : null);
//                newAuditDto.setClientName(client != null ? client.getClientName() : null);
//
//                JsonObject newEntityAudit = new JsonParser()
//                        .parse(getJsonObject(newAuditDto).toJSONString())
//                        .getAsJsonObject();
//
//                audit.creatAudit(oldEntityAudit.toString(),
//                        newEntityAudit.toString(),
//                        AuditFuctions.MODIFY,
//                        Constants.SCREEN_ID.PROJECT_PROFILE,
//                        savedProject.getClientId(),
//                        Constants.MESSAGE_STATUS.PROCESSED,
//                        messageSource.getMessage("escrowProject.va.VAL0019", null, LocaleContextHolder.getLocale()),
//                        principal);
//
//                return new ServiceResponse(Constants.MESSAGE_STATUS.SUCCESS,
//                        messageSource.getMessage("escrowProject.va.VAL0019", null, LocaleContextHolder.getLocale()), null);
//
//            } else {
//                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0018", null, LocaleContextHolder.getLocale()), null);
//            }
//        }
//    } catch (Exception e) {
//        log.error(e.getMessage(), e);
//        return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0018", null, LocaleContextHolder.getLocale()), null);
//    }
//}

    @Override
    public ServiceResponse updateProjectDetails(@Valid ESCProjectDto projectDto, Principal principal) {
        ESCProject projectObj = new ESCProject();
        boolean saveStatus = false;
        try {
            if (!checkProjectCisAndProjectId(projectDto)) {
                if (checkCisExistInClient(projectDto.getProjectCif())) {
                    return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED,
                            messageSource.getMessage("escrowProject.va.VAL0099", null, LocaleContextHolder.getLocale()), null);
                }
            }

            BigDecimal bdblretension = calcalutePercentage(projectDto.getProjectValue(), Constants.ESCROW_PROJECT.CAP_RET_PER);
            BigDecimal bdblmarketing = calcalutePercentage(projectDto.getProjectValue(), Constants.ESCROW_PROJECT.CAP_MAR_PER);
            BigDecimal bdblconstruction = calcalutePercentage(projectDto.getProjectValue(), Constants.ESCROW_PROJECT.CAP_CON_PER);

            projectDto.setProjectRetentionValue(bdblretension);
            projectDto.setProjectConsValue(bdblconstruction);
            projectDto.setProjectMarketingValue(bdblmarketing);

            projectObj = escrowProjectRepository.getProject(projectDto.getProjectId());

            if (StringUtils.isEmpty(projectObj)) {
                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED,
                        messageSource.getMessage("escrowProject.va.VAL0017", null, LocaleContextHolder.getLocale()), null);
            } else if (!isArabic(projectDto.getProjectNameArb())) {
                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED,
                        messageSource.getMessage("escrowProject.va.VAL0007", null, LocaleContextHolder.getLocale()), null);
            } else if (!isArabic(projectDto.getDeveloperName())) {
                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED,
                        messageSource.getMessage("escrowProject.va.VAL0006", null, LocaleContextHolder.getLocale()), null);
            } else if (projectObj.getStatus().equals("POSTCLOS")
                    || projectObj.getStatus().equals("POSTCLSD")
                    || projectObj.getStatus().equals("PRECLOSD")) {
                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED,
                        messageSource.getMessage("escrowProject.va.VAL0075", null, LocaleContextHolder.getLocale()), null);
            } else {

                SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");

                ESCProjectAuditDto oldAuditDto = new ESCProjectAuditDto();
                BeanUtils.copyProperties(projectObj, oldAuditDto);
                oldAuditDto.setNumberOfUnits(projectObj.getNumberOfUnits() != null ? projectObj.getNumberOfUnits().toString() : null);
                oldAuditDto.setProjectCost(projectObj.getProjectValue() != null ? projectObj.getProjectValue().toString() : null);
                oldAuditDto.setDistributionStatus(projectObj.getDistStatus() != null ? projectObj.getDistStatus().toString() : null);
                oldAuditDto.setCreatedBy(projectObj.getCreatedId());
                oldAuditDto.setCreatedDate(projectObj.getDateCreated() != null ? sdf.format(projectObj.getDateCreated()) : null);
                oldAuditDto.setModifiedBy(projectObj.getVerifiedId());
                oldAuditDto.setModifiedDate(projectObj.getDateverified() != null ? sdf.format(projectObj.getDateverified()) : null);

                Client client = clientRepository.getCustomerDetails(projectObj.getClientId());
                oldAuditDto.setClientName(client != null ? client.getClientName() : null);

                JsonObject oldEntityAudit = new JsonParser()
                        .parse(getJsonObject(oldAuditDto).toJSONString())
                        .getAsJsonObject();

                BeanUtils.copyProperties(projectDto, projectObj);

                DateFormat formatProf = new SimpleDateFormat("dd-MM-yyyy");
                if (projectDto.getProfileExpiryDate() != null) {
                    projectObj.setProfileExpiryDate(formatProf.parse(projectDto.getProfileExpiryDate()));
                }

                projectObj.setStatus("PROCESSD");
                projectObj.setLastSchmeDate(new Date());
                projectObj.setRegaLicenseExpDate(stringToDateConverter(projectDto.getStrRegaLicenceExpDate()));
                projectObj.setEndDate(stringToDateConverter(projectDto.getStrEndDate()));
                projectObj.setProjStartDate(stringToDateConverter(projectDto.getStrStartDate()));
                projectObj.setProjectName(projectDto.getProjectName());
                projectObj.setProjectNameArb(projectDto.getProjectNameArb());
                projectObj.setProjectSiteAddress1(projectDto.getProjectSiteAddress1());
                projectObj.setProjectSiteAddress2(projectDto.getProjectSiteAddress2());
                projectObj.setProjectSiteAddress3(projectDto.getProjectSiteAddress3());
                projectObj.setProjectSiteAddress4(projectDto.getProjectSiteAddress4());
                projectObj.setDeveloperName(projectDto.getDeveloperName());
                projectObj.setDeveloperNameEnglish(projectDto.getDeveloperNameEnglish());
                projectObj.setPosMerchantId(projectDto.getPosMerchantId());
                projectObj.setCreatedId(projectObj.getCreatedId());

                projectObj.setVerifiedId(principal.getName());
                projectObj.setDateverified(new Date());

                projectObj.setCommisionAccount(projectDto.getCommisionAccount());
                projectObj.setClientId(projectDto.getClientId());

                if (projectDto.getProjectType() != null) {
                    projectObj.setProjectType(projectDto.getProjectType());
                }

                if (projectDto.getStpRule() != null) {
                    projectObj.setStpRule(projectDto.getStpRule());
                }

                if (projectDto.getPosInternalAccount() != null) {
                    projectObj.setPosInternalAccount(projectDto.getPosInternalAccount());
                } else {
                    projectObj.setPosInternalAccount("");
                }

                if (projectDto.getPosChargeAccount() != null) {
                    projectObj.setPosChargeAccount(projectDto.getPosChargeAccount());
                } else {
                    projectObj.setPosChargeAccount("");
                }

                if (projectObj.getProjectValue() != projectDto.getProjectValue()) {
                    projectObj.setProjectValue(projectDto.getProjectValue());
                    projectObj.setProjectConsValue(projectDto.getProjectConsValue());
                    projectObj.setProjectMarketingValue(projectDto.getProjectMarketingValue());
                    projectObj.setProjectRetentionValue(projectDto.getProjectRetentionValue());
                }

                projectObj.setTotalArea(projectDto.getTotalArea());
                projectObj.setClientIbanCode(projectDto.getClientIbanCode());
                projectObj.setNumberOfUnits(projectDto.getNumberOfUnits());
                projectObj.setSchemeId(projectDto.getProjectId());

                FinacialAccountDetails finacialAccountDetails = new FinacialAccountDetails();
                BigDecimal zeroBigDecimal = BigDecimal.valueOf(0.00);

                finacialAccountDetails.setConstructionAccount(zeroBigDecimal);
                finacialAccountDetails.setCurrentConstructionAccount(zeroBigDecimal);
                finacialAccountDetails.setCurrentMarkettingAccount(zeroBigDecimal);
                finacialAccountDetails.setMarkettingAccount(zeroBigDecimal);
                finacialAccountDetails.setCurrentRetentionAccount(zeroBigDecimal);
                finacialAccountDetails.setRetentionAccount(zeroBigDecimal);
                finacialAccountDetails.setSurplusAccount(zeroBigDecimal);
                finacialAccountDetails.setCurrentSurplusAccount(zeroBigDecimal);
                finacialAccountDetails.setProDate(new Date());
                finacialAccountDetails.setProjectId(projectDto.getProjectId());
                finacialAccountDetails.setCurrentLoanAmount(zeroBigDecimal);
                finacialAccountDetails.setCurrentInvestmentAmount(zeroBigDecimal);
                finacialAccountDetails.setInvestmentAmount(zeroBigDecimal);
                finacialAccountDetails.setLoanAmount(zeroBigDecimal);
                finacialAccountDetails.setBankFinanceAmount(zeroBigDecimal);
                finacialAccountDetails.setCurrentBankFinanceAmount(zeroBigDecimal);
                finacialAccountDetails.setBankHafizAmount(zeroBigDecimal);
                finacialAccountDetails.setCurrentHafizAmount(zeroBigDecimal);
                finacialAccountDetails.setBankPosAmount(zeroBigDecimal);
                finacialAccountDetails.setCurrentPosAmount(zeroBigDecimal);

                saveStatus = finacialAccountDetailsRepository.save(finacialAccountDetails) != null;

                ESCProject savedProject = escrowProjectRepository.save(projectObj);

                if (savedProject != null) {

                    ESCProjectAuditDto newAuditDto = new ESCProjectAuditDto();
                    BeanUtils.copyProperties(savedProject, newAuditDto);
                    newAuditDto.setNumberOfUnits(savedProject.getNumberOfUnits() != null ? savedProject.getNumberOfUnits().toString() : null);
                    newAuditDto.setProjectCost(savedProject.getProjectValue() != null ? savedProject.getProjectValue().toString() : null);
                    newAuditDto.setDistributionStatus(savedProject.getDistStatus() != null ? savedProject.getDistStatus().toString() : null);
                    newAuditDto.setCreatedBy(savedProject.getCreatedId());
                    newAuditDto.setCreatedDate(savedProject.getDateCreated() != null ? sdf.format(savedProject.getDateCreated()) : null);
                    newAuditDto.setModifiedBy(savedProject.getVerifiedId());
                    newAuditDto.setModifiedDate(savedProject.getDateverified() != null ? sdf.format(savedProject.getDateverified()) : null);
                    newAuditDto.setClientName(client != null ? client.getClientName() : null);

                    JsonObject newEntityAudit = new JsonParser()
                            .parse(getJsonObject(newAuditDto).toJSONString())
                            .getAsJsonObject();

                    audit.creatAudit(oldEntityAudit.toString(),
                            newEntityAudit.toString(),
                            AuditFuctions.MODIFY,
                            Constants.SCREEN_ID.PROJECT_PROFILE,
                            savedProject.getClientId(),
                            Constants.MESSAGE_STATUS.PROCESSED,
                            messageSource.getMessage("escrowProject.va.VAL0019", null, LocaleContextHolder.getLocale()),
                            principal);

                    return new ServiceResponse(Constants.MESSAGE_STATUS.SUCCESS,
                            messageSource.getMessage("escrowProject.va.VAL0019", null, LocaleContextHolder.getLocale()), null);
                } else {
                    return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED,
                            messageSource.getMessage("escrowProject.va.VAL0018", null, LocaleContextHolder.getLocale()), null);
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED,
                    messageSource.getMessage("escrowProject.va.VAL0018", null, LocaleContextHolder.getLocale()), null);
        }
    }



    private boolean checkCisExistInClient(String cif) {
        try {
            if (clientRepository.getExistingCif(cif).isPresent()) {
                return true;
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return false;
    }

    //    private boolean checkCisInPrj(CustomerDetailsRqDto request) {
//        try {
//            String originalCic = request.getId();
//            String normalizedCic = originalCic.replaceFirst("^0+", "");
//
//            if (escrowProjectRepository.getExistingCicNormalized(normalizedCic).isPresent()) {
//                return true;
//            }
//        } catch (Exception e) {
//            log.error(e.getMessage(), e);
//        }
//        return false;
//    }
    private boolean checkProjectCisAndProjectId(ESCProjectDto projectDto) {

        try {
            ESCProject project = escrowProjectRepository.getProject(projectDto.getProjectId());
            if (null != project && !StringUtils.isEmpty(project) && project.getProjectId().equals(projectDto.getProjectId()) && null != project.getProjectCif() && project.getProjectCif().equals(projectDto.getProjectCif())) {
                return true;
            }

        } catch (Exception e) {
            log.info(e.getMessage(), e);
        }
        return false;
    }

    @Override
    public List<Map<String, String>> projectIdDropDown() {

        try {
            return escrowProjectRepository.findProjectIdAndName();

        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    @Override
    public ServiceResponse setDistributionAction(String projectId, Principal principal,String action) {

        boolean saveStatus = false;
        ESCProject projectObj = null;
        String statusMessage = "";
        try {

            projectObj = escrowProjectRepository.getProject(projectId);
            String oldData = getJsonObject(projectObj).toJSONString();
            if (StringUtils.isEmpty(projectObj)) {
                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0064", null, LocaleContextHolder.getLocale()), null);
            }
            else if (projectObj.getCreatedId().equals(principal.getName())) {
                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0072", null, LocaleContextHolder.getLocale()), null);
            }
            else {
                if(null!= action){
                    if ("D".equalsIgnoreCase(action)){
                        projectObj.setDistStatus(0);
                    }else if ("E".equalsIgnoreCase(action)){
                        projectObj.setDistStatus(1);
                    }
                } else {
                    return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0112", null, LocaleContextHolder.getLocale()), null);

                }
            }
            saveStatus = escrowProjectRepository.save(projectObj) != null ? true : false;

            if (saveStatus) {
//                    audit.creatAudit(oldData, getJsonObject(projectObj).toJSONString(), AuditFuctions.MODIFY, Constants.SCREEN_ID.PROJECT_PROFILE, "NIL", Constants.MESSAGE_STATUS.PROCESSED, messageSource.getMessage(statusMessage, null, LocaleContextHolder.getLocale()), principal);
                return new ServiceResponse(Constants.MESSAGE_STATUS.SUCCESS, messageSource.getMessage("escrowProject.va.VAL0107", null, LocaleContextHolder.getLocale()), null);

            } else {
                return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0108", null, LocaleContextHolder.getLocale()), null);

            }

        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return new ServiceResponse(Constants.MESSAGE_STATUS.FAILED, messageSource.getMessage("escrowProject.va.VAL0063", null, LocaleContextHolder.getLocale()), null);
        }

    }

    @Override
    public JSONObject calculateRMCValues(String strProjectId, BigDecimal dblProjectValue, Principal principal) {
        JSONObject res = null;
        try {
            res = new JSONObject();
            BigDecimal bdblretension = calcalutePercentage(dblProjectValue, Constants.ESCROW_PROJECT.CAP_RET_PER);
            BigDecimal bdblmarketing = calcalutePercentage(dblProjectValue, Constants.ESCROW_PROJECT.CAP_MAR_PER);
            BigDecimal bdblconstruction = calcalutePercentage(dblProjectValue, Constants.ESCROW_PROJECT.CAP_CON_PER);

            res.put("retention", bdblretension);
            res.put("marketing", bdblmarketing);
            res.put("construction", bdblconstruction);
            res.put("code", "success");

        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return res;

    }

    @Override
    public JSONObject downloadCertificate(String projectId, Principal principal) {
        JSONObject obj = new JSONObject();
        try {
            String logDetailsFile = projectId + "-Certificate.pdf";

            Document document = new Document();
            document.open();

            Resource resource = new ClassPathResource("logo.png");
            InputStream inputStream = resource.getInputStream();

            File fs = new File("RiyadBank-Regular.otf");
            URL url = getClass().getClassLoader().getResource("SAB-Regular.otf");

            URI uri = url.toURI();

            String processed = uri.getPath();

            SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd");
            String Date = simpleDateFormat.format(new Date());

            ByteArrayOutputStream fileOutputStream = new ByteArrayOutputStream();
            PdfWriter.getInstance(document, fileOutputStream);

            ESCProjectDto proDetails = getProjectAndAccountDetails(projectId);
            Client client = clientRepository.getCustomerDetails(proDetails.getClientId());
            String accountName = proDetails.getAccountName();
            String escAccountNo = proDetails.getEscrowIbanAccount();
            String escrowBban = escAccountNo.substring(11);
            String branchNo = escrowBban.substring(0, 3);
            String CustomerName = client.getClientName();
            String idNumber = client.getCrNumber();
            String address = client.getClientAddress();
            String telNumber = client.getClientPhone();
            String accountNoAR = "رقم الحساب الجاري";

//            String branchName = getBranchName(branchNo);
//            String branchAddress = branchNo + " ," + branchName + " Branch";

//			String CustomerAccNumber = proDetails.getnum();

            document.open();

            Font font = FontFactory.getFont(Constants.ARIAL, 10, Font.BOLD);
            // HSBC Red
            BaseColor customColor = new BaseColor(219, 0, 17, 255);

            // HSBC Grey
            BaseColor customColor2 = new BaseColor(77, 77, 79, 255);


            Font headfont = FontFactory.getFont(processed, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, 13f, Font.BOLD, new BaseColor(77, 77, 79, 255));

            Font whiteFont = FontFactory.getFont(processed, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, 14f, Font.BOLD, new BaseColor(255, 255, 255, 1));

            Font blackFont = FontFactory.getFont(processed, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, 14f, Font.NORMAL, new BaseColor(77, 77, 79, 255));
            Font normal = FontFactory.getFont(processed, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, 13f, Font.NORMAL, new BaseColor(15, 15, 15, 1));
            Font thick = FontFactory.getFont(processed, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, 15f, Font.BOLD, new BaseColor(15, 15, 15, 1));

            byte[] bdata = FileCopyUtils.copyToByteArray(inputStream);
            addImageProject(document, bdata);

            // header table

            PdfPTable headerTable = new PdfPTable(2);
            headerTable.setWidthPercentage(100);
            PdfPCell blankCell = new PdfPCell();
            blankCell.setBorder(PdfPCell.NO_BORDER);
            headerTable.addCell(blankCell);
            float[] colHeaderWidth = {5f, 10f};
            headerTable.setWidths(colHeaderWidth);

            String headerLine1 = "شهادة رقم الحساب البنكي الدولي";
            Paragraph headerpara1 = new Paragraph(headerLine1, headfont);
            PdfPCell headerLineCell1 = new PdfPCell(headerpara1);
            headerLineCell1.setBorder(PdfPCell.NO_BORDER);
            headerLineCell1.setRunDirection(PdfWriter.RUN_DIRECTION_RTL);
            headerLineCell1.setPaddingRight(10f);

            headerTable.addCell(headerLineCell1);

            String headerLine2 = "    Customer Account No./ IBAN Certificate";
            Paragraph headerpara2 = new Paragraph(headerLine2, headfont);
            PdfPCell headerLineCell2 = new PdfPCell(headerpara2);
            headerLineCell2.setBorder(PdfPCell.NO_BORDER);
            headerLineCell2.setHorizontalAlignment(PdfPCell.ALIGN_RIGHT);

//			headerTable.addCell(headerLineCell1);
            headerTable.addCell(blankCell);
            headerTable.addCell(headerLineCell2);
            headerTable.setSpacingAfter(30f);

            float widthTR = 6f; // Width of the rectangle
            float heightTR = 6f; // Height of the rectangle
            float rightMarginTR = 90f; // Margin from the right edge in points
            float topMarginTR = 35f; // Margin from the top edge in points
            float urxTR = document.getPageSize().getWidth() - rightMarginTR; // Upper-right x-coordinate
            float uryTR = document.getPageSize().getHeight() - topMarginTR; // Upper-right y-coordinate
            float llxTR = urxTR - widthTR; // Lower-left x-coordinate
            float llyTR = uryTR - heightTR; // Lower-left y-coordinate

//			Rectangle rectTopRight = new Rectangle(llxTR, llyTR, urxTR, uryTR);
//			rectTopRight.setBackgroundColor(new BaseColor(219, 0, 17, 255));

            float widthBL = 6f; // Width of the rectangle
            float heightBL = 6f; // Height of the rectangle
            float rightMarginBL = 293f; // Margin from the right edge in points
            float topMarginBL = 72f; // Margin from the top edge in points
            float urx = document.getPageSize().getWidth() - rightMarginBL; // Upper-right x-coordinate
            float ury = document.getPageSize().getHeight() - topMarginBL; // Upper-right y-coordinate
            float llx = urx - widthBL; // Lower-left x-coordinate
            float lly = ury - heightBL; // Lower-left y-coordinate

//			Rectangle rectBottomLeft = new Rectangle(llx, lly, urx, ury);
//			rectBottomLeft.setBackgroundColor(new BaseColor(219, 0, 17, 255));
//
//			document.add(rectTopRight);
//			document.add(rectBottomLeft);

            document.add(headerTable);

            // date table
            PdfPTable dateTable = new PdfPTable(3);
            dateTable.setWidthPercentage(100);
            float[] colWidth = {5f, 5f, 5f};
            dateTable.setWidths(colWidth);
            dateTable.setSpacingAfter(.9f);

            String dateEN = "Date:";
            Paragraph dateParaEn = new Paragraph(dateEN, whiteFont);
            PdfPCell dateCell1 = new PdfPCell(dateParaEn);
            dateCell1.setHorizontalAlignment(1);
            dateCell1.setBorder(PdfPCell.NO_BORDER);
            dateCell1.setBackgroundColor(new BaseColor(219, 0, 17, 255));

            dateTable.addCell(dateCell1);

            String date = Date;
            Paragraph datePara = new Paragraph(date, font);
            PdfPCell dateCell2 = new PdfPCell(datePara);
            dateCell2.setHorizontalAlignment(PdfPCell.ALIGN_CENTER);
            dateCell2.setBorder(PdfPCell.NO_BORDER);
            dateCell2.setBackgroundColor(new BaseColor(242, 242, 242));

            dateTable.addCell(dateCell2);

            String dateAR = "تاريخ";
            Paragraph dateParaAR = new Paragraph(dateAR, whiteFont);
            PdfPCell dateCell3 = new PdfPCell(dateParaAR);
            dateCell3.setHorizontalAlignment(1);
            dateCell3.setBorder(PdfPCell.NO_BORDER);
            dateCell3.setBackgroundColor(new BaseColor(219, 0, 17, 255));
            dateCell3.setRunDirection(PdfWriter.RUN_DIRECTION_RTL);

            dateTable.addCell(dateCell3);
            document.add(new Paragraph(" "));

            document.add(dateTable);
//			document.add(dateTable);

            PdfPTable nameTable = new PdfPTable(3);
            nameTable.setWidthPercentage(100);
            float[] colnameWidth = {5f, 5f, 5f};
            nameTable.setWidths(colnameWidth);
            nameTable.setSpacingAfter(.9f);

            String name = "To whom it may concern,";
            Paragraph nameParaEN = new Paragraph(name, blackFont);
            PdfPCell nameCell1 = new PdfPCell(nameParaEN);
            nameCell1.setHorizontalAlignment(PdfPCell.ALIGN_LEFT);
            nameCell1.setBorder(PdfPCell.NO_BORDER);
            nameCell1.setBackgroundColor(new BaseColor(242, 242, 242));

            nameTable.addCell(nameCell1);

            String nameEmp = "";
            Paragraph nameParaEmp = new Paragraph(nameEmp, blackFont);
            PdfPCell nameCell2 = new PdfPCell(nameParaEmp);
            nameCell2.setHorizontalAlignment(PdfPCell.ALIGN_LEFT);
            nameCell2.setBorder(PdfPCell.NO_BORDER);
            nameCell2.setBackgroundColor(new BaseColor(242, 242, 242));

            nameTable.addCell(nameCell2);

            String nameAR = "إلى من يهمه الأمر";
            Paragraph nameParaAR = new Paragraph(nameAR, blackFont);
            PdfPCell nameCell3 = new PdfPCell(nameParaAR);
            nameCell3.setHorizontalAlignment(PdfPCell.ALIGN_LEFT);
            nameCell3.setBorder(PdfPCell.NO_BORDER);
            nameCell3.setBackgroundColor(new BaseColor(242, 242, 242));
            nameCell3.setRunDirection(PdfWriter.RUN_DIRECTION_RTL);
            nameCell3.setPaddingRight(10f);

            nameTable.addCell(nameCell3);

            String name2 = "We would like to inform you that Company";
            Paragraph nameParaEN2 = new Paragraph(name2, blackFont);
            PdfPCell nameCell4 = new PdfPCell(nameParaEN2);
            nameCell4.setHorizontalAlignment(PdfPCell.ALIGN_LEFT);
            nameCell4.setBorder(PdfPCell.NO_BORDER);
            nameCell4.setBackgroundColor(new BaseColor(242, 242, 242));

            nameTable.addCell(nameCell4);

            String fullName = accountName;
            Paragraph fullNamePara = new Paragraph(fullName, blackFont);
            PdfPCell nameCell5 = new PdfPCell(fullNamePara);
            nameCell5.setHorizontalAlignment(PdfPCell.ALIGN_CENTER);
            nameCell5.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
            nameCell5.setBorder(PdfPCell.NO_BORDER);
            nameCell5.setBackgroundColor(new BaseColor(242, 242, 242));
            nameCell5.setRunDirection(PdfWriter.RUN_DIRECTION_RTL);

            nameTable.addCell(nameCell5);

            String nameAR2 = "نود اعلامكم بأن شركة";
            Paragraph nameParaAR2 = new Paragraph(nameAR2, blackFont);
            PdfPCell nameCell6 = new PdfPCell(nameParaAR2);
            nameCell6.setHorizontalAlignment(PdfPCell.ALIGN_LEFT);
            nameCell6.setBorder(PdfPCell.NO_BORDER);
            nameCell6.setBackgroundColor(new BaseColor(242, 242, 242));
            nameCell6.setRunDirection(PdfWriter.RUN_DIRECTION_RTL);
            nameCell6.setPaddingRight(10f);

            nameTable.addCell(nameCell6);

            document.add(nameTable);

            PdfPTable branchTable = new PdfPTable(3);
            float[] colbranchWidth = {5f, 5f, 5f};
            branchTable.setWidths(colbranchWidth);
            branchTable.setWidthPercentage(100);
            branchTable.setSpacingAfter(20f);

            String branchEN = "Is one of our customer at branch:";
            Paragraph branchENPara = new Paragraph(branchEN, whiteFont);
            PdfPCell branchnameCell1 = new PdfPCell(branchENPara);
            branchnameCell1.setHorizontalAlignment(PdfPCell.ALIGN_CENTER);
            branchnameCell1.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
            branchnameCell1.setBorder(PdfPCell.NO_BORDER);
            branchnameCell1.setBackgroundColor(new BaseColor(219, 0, 17, 255));
            branchnameCell1.setFixedHeight(50f);

            branchTable.addCell(branchnameCell1);

            Paragraph branchNamePara = new Paragraph("test", blackFont);
            PdfPCell branchnameCell2 = new PdfPCell(branchNamePara);
            branchnameCell2.setHorizontalAlignment(PdfPCell.ALIGN_CENTER);
            branchnameCell2.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
            branchnameCell2.setBorder(PdfPCell.NO_BORDER);
            branchnameCell2.setRunDirection(PdfWriter.RUN_DIRECTION_RTL);
            branchnameCell2.setBackgroundColor(new BaseColor(242, 242, 242));
            branchnameCell2.setFixedHeight(50f);

            String branchAR = "هو أحد عملائنا في الفرع";
            Paragraph branchARPara = new Paragraph(branchAR, whiteFont);
            PdfPCell branchnameCell3 = new PdfPCell(branchARPara);
            branchnameCell3.setHorizontalAlignment(PdfPCell.ALIGN_CENTER);
            branchnameCell3.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
            branchnameCell3.setBorder(PdfPCell.NO_BORDER);
            branchnameCell3.setBackgroundColor(new BaseColor(219, 0, 17, 255));
            branchnameCell3.setFixedHeight(50f);
            branchnameCell3.setRunDirection(PdfWriter.RUN_DIRECTION_RTL);

//            branchTable.addCell(branchnameCell2);
            branchTable.addCell(branchnameCell3);
            document.add(branchTable);

            PdfPTable disclaimerTable = new PdfPTable(2);
            float[] coldisclaimerWidth = {6f, 6f};
            disclaimerTable.setWidths(coldisclaimerWidth);
            disclaimerTable.setWidthPercentage(100);
            disclaimerTable.setSpacingAfter(5f);

            String disclaimerEn = "At his/her request below is his/her Account No. without any liability or obligation on the bank";
            Paragraph disclaimerPara = new Paragraph(disclaimerEn, blackFont);
            PdfPCell disclaimerCell1 = new PdfPCell(disclaimerPara);
            disclaimerCell1.setHorizontalAlignment(PdfPCell.ALIGN_LEFT);
            disclaimerCell1.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
            disclaimerCell1.setBorder(PdfPCell.NO_BORDER);
            disclaimerCell1.setBackgroundColor(new BaseColor(255, 255, 255));
            disclaimerCell1.setFixedHeight(90f);
            disclaimerCell1.setPaddingLeft(10f);

            disclaimerTable.addCell(disclaimerCell1);

            String disclaimerAR = "بناء على طلبه أدناه رقم حسابه دون أي مسؤولية أو التزام على البنك";
            Paragraph disclaimerPara2 = new Paragraph(disclaimerAR, blackFont);
            PdfPCell disclaimerCell2 = new PdfPCell(disclaimerPara2);
            disclaimerCell2.setHorizontalAlignment(PdfPCell.ALIGN_LEFT);
            disclaimerCell2.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
            disclaimerCell2.setBorder(PdfPCell.NO_BORDER);
            disclaimerCell2.setBackgroundColor(new BaseColor(255, 255, 255));
            disclaimerCell2.setFixedHeight(90f);
            disclaimerCell2.setRunDirection(PdfWriter.RUN_DIRECTION_RTL);

            disclaimerTable.addCell(disclaimerCell2);
            document.add(disclaimerTable);

            PdfPTable accountNoTable = new PdfPTable(2);
            float[] colaccountNoWidth = {5f, 5f};
            accountNoTable.setWidths(colaccountNoWidth);
            accountNoTable.setWidthPercentage(100);
            accountNoTable.setSpacingAfter(.9f);

            String accountNoEn = "Current Account No";
            Paragraph accountNoEnPara = new Paragraph(accountNoEn, whiteFont);
            PdfPCell accountNoCell1 = new PdfPCell(accountNoEnPara);
            accountNoCell1.setHorizontalAlignment(PdfPCell.ALIGN_LEFT);
            accountNoCell1.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
            accountNoCell1.setBorderColor(new BaseColor(219, 0, 17, 255));
            accountNoCell1.setBorder(PdfPCell.RIGHT);
            accountNoCell1.setBackgroundColor(new BaseColor(219, 0, 17, 255));
            accountNoCell1.setFixedHeight(20f);

            accountNoTable.addCell(accountNoCell1);

            Paragraph accountNoARPara = new Paragraph(accountNoAR, whiteFont);
            PdfPCell accountNoCell2 = new PdfPCell(accountNoARPara);
            accountNoCell2.setHorizontalAlignment(PdfPCell.ALIGN_LEFT);
            accountNoCell2.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
            accountNoCell2.setBorder(PdfPCell.NO_BORDER);
            accountNoCell2.setBackgroundColor(new BaseColor(219, 0, 17, 255));
            accountNoCell2.setFixedHeight(20f);
            accountNoCell2.setRunDirection(PdfWriter.RUN_DIRECTION_RTL);
            accountNoCell2.setPaddingRight(10f);

            accountNoTable.addCell(accountNoCell2);

            document.add(accountNoTable);

            PdfPTable accountNumTable = new PdfPTable(1);
            accountNumTable.setWidthPercentage(100);
            accountNumTable.setSpacingAfter(35f);

            Paragraph accountNumPara = new Paragraph(escrowBban, blackFont);
            PdfPCell accountNumCell = new PdfPCell(accountNumPara);
            accountNumCell.setHorizontalAlignment(PdfPCell.ALIGN_CENTER);
            accountNumCell.setBorder(PdfPCell.NO_BORDER);
            accountNumCell.setBackgroundColor(new BaseColor(242, 242, 242));
//			accountNumCell.setBackgroundColor(new BaseColor(255, 255, 255));
            accountNumTable.addCell(accountNumCell);

            document.add(accountNumTable);

            PdfPTable ibanTable = new PdfPTable(2);
            float[] colibanTableWidth = {5f, 5f};
            ibanTable.setWidths(colibanTableWidth);
            ibanTable.setWidthPercentage(100);
            ibanTable.setSpacingAfter(.9f);

            String ibanEN = "Consolidate Account No: IBAN";
            Paragraph ibanENPara = new Paragraph(ibanEN, whiteFont);
            PdfPCell ibanCell1 = new PdfPCell(ibanENPara);
            ibanCell1.setHorizontalAlignment(PdfPCell.ALIGN_LEFT);
            ibanCell1.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
            ibanCell1.setBorderColor(new BaseColor(255, 255, 255));
            ibanCell1.setBorder(PdfPCell.RIGHT);
            ibanCell1.setBackgroundColor(new BaseColor(219, 0, 17, 255));
            ibanCell1.setFixedHeight(20f);

            ibanTable.addCell(ibanCell1);

            String ibanAR = "رقم الحساب الموحد (الآيبان)";
            Paragraph ibanARPara = new Paragraph(ibanAR, whiteFont);
            PdfPCell ibanCell2 = new PdfPCell(ibanARPara);
            ibanCell2.setHorizontalAlignment(PdfPCell.ALIGN_LEFT);
            ibanCell2.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
            ibanCell2.setBorderColor(new BaseColor(255, 255, 255));
            ibanCell2.setBorder(PdfPCell.RIGHT);
            ibanCell2.setBackgroundColor(new BaseColor(219, 0, 17, 255));
            ibanCell2.setFixedHeight(20f);
            ibanCell2.setPaddingRight(10f);
            ibanCell2.setRunDirection(PdfWriter.RUN_DIRECTION_RTL);

            ibanTable.addCell(ibanCell2);

            document.add(ibanTable);

            PdfPTable ibanNumTable = new PdfPTable(1);
            ibanNumTable.setWidthPercentage(100);
            ibanNumTable.setSpacingAfter(35f);

            Paragraph ibanNumPara = new Paragraph(escAccountNo, blackFont);
            PdfPCell ibanNumCell = new PdfPCell(ibanNumPara);
            ibanNumCell.setHorizontalAlignment(PdfPCell.ALIGN_CENTER);
            ibanNumCell.setBorder(PdfPCell.NO_BORDER);
            ibanNumCell.setBackgroundColor(new BaseColor(255, 255, 255));
            ibanNumCell.setBackgroundColor(new BaseColor(242, 242, 242));
            ibanNumTable.addCell(ibanNumCell);

            document.add(ibanNumTable);

            PdfPTable psInfoTable = new PdfPTable(2);
            float[] colpsInfoTableWidth = {5f, 5f};
            psInfoTable.setWidths(colpsInfoTableWidth);
            psInfoTable.setWidthPercentage(100);
            psInfoTable.setSpacingAfter(5f);

            String psInfoEN = "Customer Personal Information";
            Paragraph psInfoENPara = new Paragraph(psInfoEN, whiteFont);
            PdfPCell psInfoCell1 = new PdfPCell(psInfoENPara);
            psInfoCell1.setHorizontalAlignment(PdfPCell.ALIGN_LEFT);
            psInfoCell1.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
            psInfoCell1.setBorderColor(new BaseColor(255, 255, 255));
            psInfoCell1.setBorder(PdfPCell.RIGHT);
            psInfoCell1.setBackgroundColor(new BaseColor(219, 0, 17, 255));
            psInfoCell1.setFixedHeight(20f);
            psInfoTable.addCell(psInfoCell1);

            String psInfoAR = "المعلومات الشخصية للعملاء";
            Paragraph psInfoARPara = new Paragraph(psInfoAR, whiteFont);
            PdfPCell psInfoCell2 = new PdfPCell(psInfoARPara);
            psInfoCell2.setHorizontalAlignment(PdfPCell.ALIGN_LEFT);
            psInfoCell2.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
            psInfoCell2.setBorderColor(new BaseColor(255, 255, 255));
            psInfoCell2.setBorder(PdfPCell.RIGHT);
            psInfoCell2.setBackgroundColor(new BaseColor(219, 0, 17, 255));
            psInfoCell2.setFixedHeight(20f);
            psInfoCell2.setPaddingRight(10f);

            psInfoCell2.setRunDirection(PdfWriter.RUN_DIRECTION_RTL);
            psInfoTable.addCell(psInfoCell2);

            document.add(psInfoTable);

            // Name
            PdfPTable nameInputTable = new PdfPTable(3);
            float[] colNameInpputWidth = {5f, 5f, 5f};
            nameInputTable.setWidths(colbranchWidth);
            nameInputTable.setWidthPercentage(100);
            nameInputTable.setSpacingAfter(2f);

            String nameInputEN = "Name:";
            Paragraph nameInputENPara = new Paragraph(nameInputEN, whiteFont);
            PdfPCell nameInputCell1 = new PdfPCell(nameInputENPara);
            nameInputCell1.setHorizontalAlignment(PdfPCell.ALIGN_CENTER);
            nameInputCell1.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
            nameInputCell1.setBorder(PdfPCell.NO_BORDER);
            nameInputCell1.setBackgroundColor(new BaseColor(219, 0, 17, 255));
            nameInputCell1.setFixedHeight(20f);

            nameInputTable.addCell(nameInputCell1);

            Paragraph nameInputpara = new Paragraph(CustomerName, blackFont);
            PdfPCell nameInputCell2 = new PdfPCell(nameInputpara);
            nameInputCell2.setHorizontalAlignment(PdfPCell.ALIGN_CENTER);
            nameInputCell2.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
            nameInputCell2.setBorder(PdfPCell.NO_BORDER);
            nameInputCell2.setRunDirection(PdfWriter.RUN_DIRECTION_RTL);
            nameInputCell2.setBackgroundColor(new BaseColor(242, 242, 242));
            nameInputCell2.setFixedHeight(20f);

            String nameInputAR = "اسم";
            Paragraph nameInputARPara = new Paragraph(nameInputAR, whiteFont);
            PdfPCell nameInputCell3 = new PdfPCell(nameInputARPara);
            nameInputCell3.setHorizontalAlignment(PdfPCell.ALIGN_CENTER);
            nameInputCell3.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
            nameInputCell3.setBorder(PdfPCell.NO_BORDER);
            nameInputCell3.setBackgroundColor(new BaseColor(219, 0, 17, 255));
            nameInputCell3.setFixedHeight(20f);
            nameInputCell3.setRunDirection(PdfWriter.RUN_DIRECTION_RTL);

            nameInputTable.addCell(nameInputCell2);
            nameInputTable.addCell(nameInputCell3);

            document.add(nameInputTable);

            // IdNo
            PdfPTable idNoInputTable = new PdfPTable(3);
            float[] idNoInpputWidth = {5f, 5f, 5f};
            idNoInputTable.setWidths(colbranchWidth);
            idNoInputTable.setWidthPercentage(100);
            idNoInputTable.setSpacingAfter(2f);

            String idNoInputEN = "ID No:";
            Paragraph idNoInputENPara = new Paragraph(idNoInputEN, whiteFont);
            PdfPCell idNoInputCell1 = new PdfPCell(idNoInputENPara);
            idNoInputCell1.setHorizontalAlignment(PdfPCell.ALIGN_CENTER);
            idNoInputCell1.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
            idNoInputCell1.setBorder(PdfPCell.NO_BORDER);
            idNoInputCell1.setBackgroundColor(new BaseColor(219, 0, 17, 255));
            idNoInputCell1.setFixedHeight(20f);

            idNoInputTable.addCell(idNoInputCell1);

            Paragraph idNoInputpara = new Paragraph(idNumber, blackFont);
            PdfPCell idNoInputCell2 = new PdfPCell(idNoInputpara);
            idNoInputCell2.setHorizontalAlignment(PdfPCell.ALIGN_CENTER);
            idNoInputCell2.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
            idNoInputCell2.setBorder(PdfPCell.NO_BORDER);
            idNoInputCell2.setRunDirection(PdfWriter.RUN_DIRECTION_RTL);
            idNoInputCell2.setBackgroundColor(new BaseColor(242, 242, 242));
            idNoInputCell2.setFixedHeight(20f);

            String idNoInputAR = "رقم السجل التجاري";
            Paragraph idNoInputARPara = new Paragraph(idNoInputAR, whiteFont);
            PdfPCell idNoInputCell3 = new PdfPCell(idNoInputARPara);
            idNoInputCell3.setHorizontalAlignment(PdfPCell.ALIGN_CENTER);
            idNoInputCell3.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
            idNoInputCell3.setBorder(PdfPCell.NO_BORDER);
            idNoInputCell3.setBackgroundColor(new BaseColor(219, 0, 17, 255));
            idNoInputCell3.setFixedHeight(20f);
            idNoInputCell3.setRunDirection(PdfWriter.RUN_DIRECTION_RTL);

            idNoInputTable.addCell(idNoInputCell2);
            idNoInputTable.addCell(idNoInputCell3);

            document.add(idNoInputTable);

            // Address
            PdfPTable addressInputTable = new PdfPTable(3);
            float[] addressInpputWidth = {5f, 5f, 5f};
            addressInputTable.setWidths(colbranchWidth);
            addressInputTable.setWidthPercentage(100);
            addressInputTable.setSpacingAfter(2f);

            String addressInputEN = "Address:";
            Paragraph addressInputENPara = new Paragraph(addressInputEN, whiteFont);
            PdfPCell addressInputCell1 = new PdfPCell(addressInputENPara);
            addressInputCell1.setHorizontalAlignment(PdfPCell.ALIGN_CENTER);
            addressInputCell1.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
            addressInputCell1.setBorder(PdfPCell.NO_BORDER);
            addressInputCell1.setBackgroundColor(new BaseColor(219, 0, 17, 255));
            addressInputCell1.setFixedHeight(20f);

            addressInputTable.addCell(addressInputCell1);

            Paragraph addressInputpara = new Paragraph(address, blackFont);
            PdfPCell addressInputCell2 = new PdfPCell(addressInputpara);
            addressInputCell2.setHorizontalAlignment(PdfPCell.ALIGN_CENTER);
            addressInputCell2.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
            addressInputCell2.setBorder(PdfPCell.NO_BORDER);
            addressInputCell2.setRunDirection(PdfWriter.RUN_DIRECTION_RTL);
            addressInputCell2.setBackgroundColor(new BaseColor(242, 242, 242));
            addressInputCell2.setFixedHeight(20f);

            String addressInputAR = "عنوان";
            Paragraph addressInputARPara = new Paragraph(addressInputAR, whiteFont);
            PdfPCell addressInputCell3 = new PdfPCell(addressInputARPara);
            addressInputCell3.setHorizontalAlignment(PdfPCell.ALIGN_CENTER);
            addressInputCell3.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
            addressInputCell3.setBorder(PdfPCell.NO_BORDER);
            addressInputCell3.setBackgroundColor(new BaseColor(219, 0, 17, 255));
            addressInputCell3.setFixedHeight(20f);
            addressInputCell3.setRunDirection(PdfWriter.RUN_DIRECTION_RTL);

            addressInputTable.addCell(addressInputCell2);
            addressInputTable.addCell(addressInputCell3);

            document.add(addressInputTable);

            /// TelNo
            PdfPTable telNoInputTable = new PdfPTable(3);
            float[] telNoInpputWidth = {5f, 5f, 5f};
            telNoInputTable.setWidths(colbranchWidth);
            telNoInputTable.setWidthPercentage(100);
            telNoInputTable.setSpacingAfter(2f);

            String telNoInputEN = "Tel No:";
            Paragraph telNoInputENPara = new Paragraph(telNoInputEN, whiteFont);
            PdfPCell telNoInputCell1 = new PdfPCell(telNoInputENPara);
            telNoInputCell1.setHorizontalAlignment(PdfPCell.ALIGN_CENTER);
            telNoInputCell1.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
            telNoInputCell1.setBorder(PdfPCell.NO_BORDER);
            telNoInputCell1.setBackgroundColor(new BaseColor(219, 0, 17, 255));
            telNoInputCell1.setFixedHeight(20f);

            telNoInputTable.addCell(telNoInputCell1);

            Paragraph telNoInputpara = new Paragraph(telNumber, blackFont);
            PdfPCell telNoInputCell2 = new PdfPCell(telNoInputpara);
            telNoInputCell2.setHorizontalAlignment(PdfPCell.ALIGN_CENTER);
            telNoInputCell2.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
            telNoInputCell2.setBorder(PdfPCell.NO_BORDER);
            telNoInputCell2.setRunDirection(PdfWriter.RUN_DIRECTION_RTL);
            telNoInputCell2.setBackgroundColor(new BaseColor(242, 242, 242));
            telNoInputCell2.setFixedHeight(20f);

            String telNoInputAR = "رقم الهاتف";
            Paragraph telNoInputARPara = new Paragraph(telNoInputAR, whiteFont);
            PdfPCell telNoInputCell3 = new PdfPCell(telNoInputARPara);
            telNoInputCell3.setHorizontalAlignment(PdfPCell.ALIGN_CENTER);
            telNoInputCell3.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
            telNoInputCell3.setBorder(PdfPCell.NO_BORDER);
            telNoInputCell3.setBackgroundColor(new BaseColor(219, 0, 17, 255));
            telNoInputCell3.setFixedHeight(20f);
            telNoInputCell3.setRunDirection(PdfWriter.RUN_DIRECTION_RTL);

            telNoInputTable.addCell(telNoInputCell2);
            telNoInputTable.addCell(telNoInputCell3);

            document.add(telNoInputTable);

            // Signature
            PdfPTable signatureInputTable = new PdfPTable(3);
            float[] signatureInpputWidth = {5f, 5f, 5f};
            signatureInputTable.setWidths(colbranchWidth);
            signatureInputTable.setWidthPercentage(100);
            signatureInputTable.setSpacingAfter(2f);

            String signatureInputEN = "Signature:";
            Paragraph signatureInputENPara = new Paragraph(signatureInputEN, whiteFont);
            PdfPCell signatureInputCell1 = new PdfPCell(signatureInputENPara);
            signatureInputCell1.setHorizontalAlignment(PdfPCell.ALIGN_CENTER);
            signatureInputCell1.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
            signatureInputCell1.setBorder(PdfPCell.NO_BORDER);
            signatureInputCell1.setBackgroundColor(new BaseColor(219, 0, 17, 255));
            signatureInputCell1.setFixedHeight(20f);

            signatureInputTable.addCell(signatureInputCell1);

            String signatureInput = "";
            Paragraph signatureInputpara = new Paragraph(signatureInput, blackFont);
            PdfPCell signatureInputCell2 = new PdfPCell(signatureInputpara);
            signatureInputCell2.setHorizontalAlignment(PdfPCell.ALIGN_CENTER);
            signatureInputCell2.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
            signatureInputCell2.setBorder(PdfPCell.NO_BORDER);
            signatureInputCell2.setRunDirection(PdfWriter.RUN_DIRECTION_RTL);
            signatureInputCell2.setBackgroundColor(new BaseColor(242, 242, 242));
            signatureInputCell2.setFixedHeight(20f);

            String signatureInputAR = "التوقيع";
            Paragraph signatureInputARPara = new Paragraph(signatureInputAR, whiteFont);
            PdfPCell signatureInputCell3 = new PdfPCell(signatureInputARPara);
            signatureInputCell3.setHorizontalAlignment(PdfPCell.ALIGN_CENTER);
            signatureInputCell3.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
            signatureInputCell3.setBorder(PdfPCell.NO_BORDER);
            signatureInputCell3.setBackgroundColor(new BaseColor(219, 0, 17, 255));
            signatureInputCell3.setFixedHeight(20f);
            signatureInputCell3.setRunDirection(PdfWriter.RUN_DIRECTION_RTL);
            signatureInputTable.addCell(signatureInputCell2);
            signatureInputTable.addCell(signatureInputCell3);

            document.add(signatureInputTable);

            document.close();
            log.info("Completed Arabic");

            // if(clientDetails.size()!=1) {
            obj.put("status", true);
            byte[] isr = fileOutputStream.toByteArray();
            byte[] encoded = Base64.getEncoder().encode(isr);
            String enc = new String(encoded);
            String fileName = logDetailsFile;
            obj.put(Constants.DATA, enc);
            obj.put(Constants.FILE_NAME, fileName);
            obj.put(Constants.CONTENT_TYPE, Constants.PDF_CONTENT_TYPE);

            return obj;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            obj = new JSONObject();
            return obj;
        }

    }

    public void addImageProject(Document document, byte[] imageArray) {
        try {
            Image logo = Image.getInstance(imageArray);
            logo.scaleAbsolute(120f, 40f);
            logo.setAbsolutePosition(document.left() + 20, document.top() - 38);
            logo.setAlignment(Image.ALIGN_RIGHT);
            document.add(logo);
        } catch (DocumentException e) {
            log.error(e.getMessage(), e);
        } catch (MalformedURLException e) {
            log.error(e.getMessage(), e);
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    public ESCProjectDto getProjectAndAccountDetails(String projectId) {
        ESCProject newProjectObj = null;
        ESCProjectDto projectDto = null;
        EscrowAccountDetails escrowAccountDetails = null;
        Client client = null;
        SimpleDateFormat dateFormat = null;
        try {
            dateFormat = new SimpleDateFormat("dd-MM-yyyy");
            projectDto = new ESCProjectDto();
            newProjectObj = escrowProjectRepository.getProject(projectId);
            escrowAccountDetails = escrowAccountDetailsRepository.getEscrowAccountDetails(projectId);
            client = clientRepository.getCustomerDetails(newProjectObj.getClientId());
            BeanUtils.copyProperties(newProjectObj, projectDto);
            projectDto.setCustCicNo(client.getCustomerBaseNumber());
            if (null != newProjectObj.getEscrowAccount()) {
                projectDto.setEscrowAccount(newProjectObj.getEscrowAccount());
            }
            if (null != newProjectObj.getProjStartDate()) {

                projectDto.setStrStartDate(dateFormat.format(newProjectObj.getProjStartDate()));
                projectDto.setStrEndDate(dateFormat.format(newProjectObj.getEndDate()));
            }
            if (newProjectObj.getProfileExpiryDate() != null) {
                projectDto.setProfileExpiryDate(dateFormat.format(newProjectObj.getProfileExpiryDate()));
            }

            if (newProjectObj.getProfileId() != null) {
                projectDto.setProfileId(newProjectObj.getProfileId());
            }
            if (newProjectObj.getSecProfileId() != null) {
                projectDto.setSecProfileId(newProjectObj.getSecProfileId());
            }

            if (!StringUtils.isEmpty(escrowAccountDetails)) {
                projectDto.setEscrowIbanAccount(escrowAccountDetails.getEscrowIbanAccount());
                projectDto.setCustomerIbanAccount(escrowAccountDetails.getCustomerIbanAccount());

                projectDto.setEscrowBbanAccount(escrowAccountDetails.getEscrowBbanAccount());
                projectDto.setCustomerBbanAccount(escrowAccountDetails.getCustomerBbanAccount());

                projectDto.setConsSubAccount(escrowAccountDetails.getConsSubAccount());
                projectDto.setConstructionSource(escrowAccountDetails.getConstructionSource());
                projectDto.setConsPercenatge(escrowAccountDetails.getConsPercenatge());

                projectDto.setMarkettingSource(escrowAccountDetails.getMarkettingSource());
                projectDto.setMarSubAccount(escrowAccountDetails.getMarSubAccount());
                projectDto.setMarPercenatge(escrowAccountDetails.getMarPercenatge());

                projectDto.setRetensionSource(escrowAccountDetails.getRetensionSource());
                projectDto.setRetSubAccount(escrowAccountDetails.getRetSubAccount());
                projectDto.setRetPercenatge(escrowAccountDetails.getRetPercenatge());

                projectDto.setCustomerAccountName(escrowAccountDetails.getCustomerAccountName());
                projectDto.setEscrowAccountName(escrowAccountDetails.getEscrowAccountName());
                projectDto.setProjectCif(escrowAccountDetails.getEscrowCicnumber());
                projectDto.setEscrowAccountCur(escrowAccountDetails.getEscrowAccountCurrency());

                projectDto.setStpRule(newProjectObj.getStpRule());
                projectDto.setAdvanceCancelRule(newProjectObj.getAdvanceCancelRule());

            } else {
                projectDto.setConstructionSource(1);
                projectDto.setMarkettingSource(1);
                projectDto.setRetensionSource(2);
            }
            projectDto.setEscrowIbanAccount(newProjectObj.getEscrowAccount());
        } catch (Exception e) {
            log.error(e.getMessage(), e);

        }
        return projectDto;
    }


}
