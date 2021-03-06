package com.prgrms.offer.domain.offer.model.entity;

import com.prgrms.offer.domain.article.model.entity.Article;
import com.prgrms.offer.domain.member.model.entity.Member;
import lombok.*;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Table(indexes = {
        @Index(name = "offer_idx_article_id_is_Selected", columnList = "article_id, isSelected"),
        @Index(name = "offer_idx_article_id", columnList = "article_id")
})
public class Offer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "offer_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id", referencedColumnName = "article_id")
    private Article article;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "offerer_id", referencedColumnName = "member_id")
    private Member offerer;

    @Column
    private Integer price;

    @Column
    private Boolean isSelected;

    @Column
    private LocalDateTime createdDate;

    public void selectOffer() {
        this.isSelected = true;
    }
}
