package com.prgrms.offer.domain.message.model.dto;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.prgrms.offer.domain.member.model.entity.Member;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class MessageRoomResponse {

    private UserInfo userInfo;

    private String productImageUrl;

    private MessageInfo message;

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class UserInfo {

        private String userNickName;
        private String userProfileImageUrl;
        private String address;

        private UserInfo(String userNickName, String userProfileImageUrl, String address) {
            this.userNickName = userNickName;
            this.userProfileImageUrl = userProfileImageUrl;
            this.address = address;
        }

        public static UserInfo nullUserInfo() {
            return new UserInfo(null, null, null);
        }

        public static UserInfo createUserInfo(Member member){
            return new UserInfo(member.getNickname(), member.getProfileImage(), member.getAddress());
        }
    }

    @AllArgsConstructor
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class MessageInfo {

        private String content;
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss", timezone = "Asia/Seoul")
        private LocalDateTime createdDate;

    }

}
