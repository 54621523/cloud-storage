package demo.cloud.auth.dto;

import lombok.Data;

@Data
public class RegisterResponse {


    private Long userId;
    private String username;
    private String email;
    private String phone;
}