package com.utechat.data.local.dao;
import androidx.room.Embedded;
import com.utechat.data.local.entity.ContactRequestEntity;
/** JOIN tên người gửi và người nhận để lời mời đọc được khi mất mạng. */
public class ContactRequestWithUser {
    @Embedded public ContactRequestEntity request;
    public String requesterName,addresseeName;
}
