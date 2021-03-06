package com.prgrms.offer.domain.article.service;

import com.prgrms.offer.common.message.ResponseMessage;
import com.prgrms.offer.common.utils.S3ImageUploader;
import com.prgrms.offer.core.config.PropertyProvider;
import com.prgrms.offer.core.error.exception.BusinessException;
import com.prgrms.offer.core.jwt.JwtAuthentication;
import com.prgrms.offer.domain.article.model.dto.*;
import com.prgrms.offer.domain.article.model.entity.Article;
import com.prgrms.offer.domain.article.model.entity.ProductImage;
import com.prgrms.offer.domain.article.model.value.Category;
import com.prgrms.offer.domain.article.model.value.TradeStatus;
import com.prgrms.offer.domain.article.repository.ArticleRepository;
import com.prgrms.offer.domain.article.repository.LikeArticleRepository;
import com.prgrms.offer.domain.article.repository.ProductImageRepository;
import com.prgrms.offer.domain.article.repository.TemporalArticle;
import com.prgrms.offer.domain.member.model.entity.Member;
import com.prgrms.offer.domain.member.repository.MemberRepository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.prgrms.offer.domain.message.repository.MessageRoomRepository;
import com.prgrms.offer.domain.offer.model.entity.Offer;
import com.prgrms.offer.domain.offer.repository.OfferRepository;
import com.prgrms.offer.domain.review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class ArticleService {

    private final ArticleRepository articleRepository;
    private final MemberRepository memberRepository;
    private final ProductImageRepository productImageRepository;
    private final LikeArticleRepository likeArticleRepository;
    private final OfferRepository offerRepository;
    private final ReviewRepository reviewRepository;
    private final MessageRoomRepository messageRoomRepository;
    private final ArticleConverter converter;
    private final S3ImageUploader s3ImageUploader;

    private final PropertyProvider propertyProvider;

    public CodeAndNameInfosResponse getAllCodeAndNameInfos() {
        return converter.toCodeAndNameInfosResponse();
    }

    public List<String> uploadImage(List<MultipartFile> images) throws IOException {
        List<String> imageUrls = new ArrayList<>();

        for (var image : images) {
            String uploadedImageUrl = s3ImageUploader.upload(image, propertyProvider.getPRODUCT_IMG_DIR());
            imageUrls.add(uploadedImageUrl);
        }

        return imageUrls;
    }

    @Transactional
    public ArticleCreateOrUpdateResponse createOrUpdate(ArticleCreateOrUpdateRequest request, JwtAuthentication authentication) {
        String loginId = authentication.loginId;

        Member writer = memberRepository.findByPrincipal(loginId)
                .orElseThrow(() -> new BusinessException(ResponseMessage.MEMBER_NOT_FOUND));

        Article articleEntity = null;

        if (request.getId() == null || request.getId().longValue() == 0) { // ?????? ????????? ??????
            Article article = converter.toEntity(request, writer);
            articleEntity = articleRepository.save(article);
        } else {  // ????????? ??????
            articleEntity = articleRepository.findById(request.getId())
                    .orElseThrow(() -> new BusinessException(ResponseMessage.ARTICLE_NOT_FOUND));

            validateWriterOrElseThrow(articleEntity, loginId);

            articleEntity.updateInfo(
                    request.getTitle(),
                    request.getContent(),
                    Category.of(request.getCategoryCode()).getCode(),
                    request.getTradeArea(),
                    request.getQuantity(),
                    request.getPrice()
            );

            var firstImgUrl = request.getImageUrls().get(0);
            articleEntity.updateMainImageUrl(
                    firstImgUrl.equals(propertyProvider.getNO_IMG()) || firstImgUrl == null || firstImgUrl.isEmpty() ?
                            null : request.getImageUrls().get(0)
            );

            productImageRepository.deleteAllByArticle(articleEntity);
        }

        saveImagseUrls(articleEntity, request.getImageUrls());

        return converter.toArticleCreateOrUpdateResponse(articleEntity);
    }

    @Transactional
    public void updateTradeStatus(Long articleId, int code, String loginId) {
        Article article = articleRepository.findById(articleId)
                .orElseThrow(() -> new BusinessException(ResponseMessage.ARTICLE_NOT_FOUND));

        validateWriterOrElseThrow(article, loginId);

        article.updateTradeStatusCode(TradeStatus.of(code).getCode());
    }

    @Transactional(readOnly = true)
    public ProductImageUrlsResponse findAllImageUrls(Long articleId) {
        if (!articleRepository.existsById(articleId)) {
            throw new BusinessException(ResponseMessage.ARTICLE_NOT_FOUND);
        }

        List<ProductImage> productImages = productImageRepository.findAllByArticleId(articleId);

        var response = new ProductImageUrlsResponse();
        for (var productImage : productImages) {
            response.getImageUrls().add(productImage.getImageUrl());
        }

        int curSize = response.getImageUrls().size();
        for (int i = 1; i <= propertyProvider.getNUM_OF_REGISTERABLE_IMG() - curSize; i++) {
            response.getImageUrls().add(null);
        }

        return response;
    }

    @Transactional
    public void deleteOne(Long articleId, String loginId) {
        Article article = articleRepository.findById(articleId)
                .orElseThrow(() -> new BusinessException(ResponseMessage.ARTICLE_NOT_FOUND));

        validateWriterOrElseThrow(article, loginId);

        doOnDeleteSetNull(article);

        articleRepository.delete(article);
    }

    @Transactional
    public ArticleDetailResponse findById(Long articleId, Optional<JwtAuthentication> authenticationOptional) {
        Article article = articleRepository.findById(articleId)
                .orElseThrow(() -> new BusinessException(ResponseMessage.ARTICLE_NOT_FOUND));

        boolean isLiked = false;
        if (authenticationOptional.isPresent()) {
            Member currentMember = memberRepository.findByPrincipal(authenticationOptional.get().loginId)
                    .orElseThrow(() -> new BusinessException(ResponseMessage.MEMBER_NOT_FOUND));

            isLiked = likeArticleRepository.existsByMemberAndArticle(currentMember, article);
        }

        article.addViewCount();

        return converter.toArticleDetailResponse(article, isLiked);
    }

    @Transactional(readOnly = true)
    public Page<ArticleBriefViewResponse> findAllByPages(
            Pageable pageable,
            Optional<Integer> categoryCodeOptional,
            Optional<Long> memberIdOptional,
            Optional<Integer> tradeStatusCodeOptional,
            Optional<JwtAuthentication> authenticationOptional
    ) {

        Page<Article> postPage;
        if (categoryCodeOptional.isEmpty() && memberIdOptional.isEmpty() && tradeStatusCodeOptional.isEmpty()) {
            postPage = articleRepository.findAll(pageable);
        }
        else if (categoryCodeOptional.isPresent() && memberIdOptional.isEmpty() && tradeStatusCodeOptional.isEmpty()) {
            postPage = articleRepository.findAllByCategoryCode(pageable, Category.of(categoryCodeOptional.get()).getCode());
        }
        else if (categoryCodeOptional.isEmpty() && memberIdOptional.isPresent() && tradeStatusCodeOptional.isPresent()) {
            if (TradeStatus.of(tradeStatusCodeOptional.get()).getCode() == TradeStatus.COMPLETED.getCode()) { // ?????? ????????? ????????????
                postPage = articleRepository.findAllByWriterIdAndTradeStatusCode(
                        pageable,
                        memberIdOptional.get(),
                        TradeStatus.of(tradeStatusCodeOptional.get()).getCode()
                );
            } else {  // ????????? ?????? ???????????? ????????????
                postPage = articleRepository.findAllByWriterIdAndTradeInProgress(pageable, memberIdOptional.get());
            }
        }
        else if (categoryCodeOptional.isEmpty() && memberIdOptional.isEmpty() && tradeStatusCodeOptional.isPresent()) {
            if (TradeStatus.of(tradeStatusCodeOptional.get()).getCode() == TradeStatus.COMPLETED.getCode()) { // ?????? ????????? ????????????
                postPage = articleRepository.findAllByTradeStatusCode(pageable, TradeStatus.of(tradeStatusCodeOptional.get()).getCode());
            } else {  // ????????? ?????? ???????????? ????????????
                postPage = articleRepository.findAllByTradeInProgress(pageable);
            }
        }
        else if (categoryCodeOptional.isEmpty() && memberIdOptional.isPresent() && tradeStatusCodeOptional.isEmpty()) {
            postPage = articleRepository.findAllByWriterId(pageable, memberIdOptional.get());
        }
        else {
            throw new BusinessException(ResponseMessage.NOT_SUPPORTING_PARAM_COMBINATION);
        }

        if (!authenticationOptional.isPresent()) {
            return postPage.map(p -> converter.toArticleBriefViewResponse(p, false));
        }

        Member currentMember = memberRepository.findByPrincipal(authenticationOptional.get().loginId)
                .orElseThrow(() -> new BusinessException(ResponseMessage.MEMBER_NOT_FOUND));

        return postPage.map(p -> makeBriefViewResponseWithLikeInfo(p, currentMember));
    }

    @Transactional(readOnly = true)
    public Page<ArticleBriefViewResponse> findAllBoughtProducts(Pageable pageable, JwtAuthentication authentication) {
        Member member = memberRepository.findByPrincipal(authentication.loginId)
                .orElseThrow(() -> new BusinessException(ResponseMessage.MEMBER_NOT_FOUND));

        Page<Offer> offerPage = offerRepository.findAllByOffererAndIsSelected(pageable, member, true);

        return offerPage.map(o -> extractArticleBriefViewResponseFromOfferEntityWithLikeInfo(o, member));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    ArticleBriefViewResponse extractArticleBriefViewResponseFromOfferEntityWithLikeInfo(Offer offer, Member member) {
        Article article = offer.getArticle();

        boolean isLiked = likeArticleRepository.existsByMemberAndArticle(member, article);

        return converter.toArticleBriefViewResponse(article, isLiked);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    ArticleBriefViewResponse makeBriefViewResponseWithLikeInfo(Article article, Member currentMember) {
        boolean isLiked = likeArticleRepository.existsByMemberAndArticle(currentMember, article);

        return converter.toArticleBriefViewResponse(article, isLiked);
    }

    @Transactional(readOnly = true)
    public Page<ArticleBriefViewResponse> findAllByMyOffers(Pageable pageable, int tradeStatusCode, JwtAuthentication authentication) {
        Member offerer = memberRepository.findByPrincipal(authentication.loginId)
                .orElseThrow(() -> new BusinessException(ResponseMessage.MEMBER_NOT_FOUND));

        Page<TemporalArticle> articlePage;
        if(tradeStatusCode == TradeStatus.COMPLETED.getCode()) {
            articlePage = articleRepository.findAllByOffererAndTradeStatusCode(offerer, tradeStatusCode, pageable);
        }else{
            articlePage = articleRepository.findAllByOffererAndTradeInProgress(offerer, pageable);
        }

        return articlePage.map(ta -> makeBriefViewResponseWithLikeInfoFromTemporalArticle(ta, offerer));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    ArticleBriefViewResponse makeBriefViewResponseWithLikeInfoFromTemporalArticle(TemporalArticle temporalArticle, Member currentMember) {
        boolean isLiked = likeArticleRepository.existsByMemberAndArticleId(currentMember, temporalArticle.getId());

        return converter.toArticleBriefViewResponse(temporalArticle, isLiked);
    }

    private void validateWriterOrElseThrow(Article article, String principal) {
        if (!article.validateWriterByPrincipal(principal)) {
            throw new BusinessException(ResponseMessage.PERMISSION_DENIED);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void saveImagseUrls(Article article, List<String> imageUrls) {
        for (var imageUrl : imageUrls) {
            if (imageUrl.equals(propertyProvider.getNO_IMG()) || imageUrl == null || imageUrl.isEmpty()) {
                continue;
            }

            var productImage = new ProductImage(imageUrl, article);
            productImageRepository.save(productImage);
        }
    }

    public Page<ArticleBriefViewResponse> getLikeArticlesWithTradeStatusCode(
            Pageable pageable, JwtAuthentication authentication, Integer tradeStatusCode) {
        Member member = memberRepository.findByPrincipal(authentication.loginId)
                .orElseThrow(() -> new BusinessException(ResponseMessage.MEMBER_NOT_FOUND));

        if (tradeStatusCode == TradeStatus.COMPLETED.getCode()) {
            return articleRepository
                    .findLikedCompletedArticleByMember(member.getId(), pageable)
                    .map(p -> makeBriefViewResponseWithLikeInfo(p, member));
        } else {
            return articleRepository
                    .findLikedSellingArticleByMember(member.getId(), pageable)
                    .map(p -> makeBriefViewResponseWithLikeInfo(p, member));
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void doOnDeleteSetNull(Article article) {
        productImageRepository.doOnDeleteSetNullFromArticle(article);
        likeArticleRepository.doOnDeleteSetNullFromArticle(article);
        offerRepository.doOnDeleteSetNullFromArticle(article);
        reviewRepository.doOnDeleteSetNullFromArticle(article);
        messageRoomRepository.doOnDeleteSetNullFromArticle(article);
    }
}
