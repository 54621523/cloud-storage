package demo.cloud.auth.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

@Setter
@Getter
@NoArgsConstructor
@TableName("user")
public class User implements UserDetails {


    @TableId(type = IdType.AUTO)
    private Long userId;
    //昵称，可重复
    private String nickname;
    // 用户名，唯一登录凭证
    private String username;
    // 密码
    @JsonIgnore
    private String password;
    // 邮箱
    private String email;
    // 手机号码
    private String phone;


    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }


    @Override
    public boolean isAccountNonExpired() {
        return true; // 账户是否未过期
    }

    @Override
    public boolean isAccountNonLocked() {
        return true; // 账户是否未锁定
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true; // 凭证(密码)是否未过期
    }

    @Override
    public boolean isEnabled() {
        return true; // 账户是否可用
    }
}
