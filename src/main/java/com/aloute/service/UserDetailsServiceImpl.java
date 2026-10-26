package com.aloute.service;

import com.aloute.model.entity.User;
import com.aloute.security.AloutePrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {
    private final AuthenticationUserCache authenticationUserCache;

    public UserDetailsServiceImpl(AuthenticationUserCache authenticationUserCache) {
        this.authenticationUserCache = authenticationUserCache;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = authenticationUserCache.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Không tìm thấy user: " + username));
        return new AloutePrincipal(user);
    }
}
