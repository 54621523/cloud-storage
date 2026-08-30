package demo.cloud.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import demo.cloud.auth.mapper.UserMapper;
import demo.cloud.auth.pojo.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;


@Service
public class UserService implements UserDetailsService {

    private final UserMapper userMapper;

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String loginAccount) throws UsernameNotFoundException {
        User user =userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, loginAccount)
                .or()
                .eq(User::getEmail, loginAccount)
                .or()
                .eq(User::getPhone, loginAccount)
        );

        if(user == null){
            throw new UsernameNotFoundException("账号或密码错误");
        }
        return user;
    }
}
