package com.aloute.security;

import com.aloute.model.entity.User;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

public class AloutePrincipal implements UserDetails {

    private static final long serialVersionUID = 1L;

    private final Long id;
    @lombok.Getter private final java.time.Instant tokensRevokedAt;
    private final String username;
    private final String password;
    private final String fullName;
    private final String avatarUrl;
    private final boolean active;
    private final List<GrantedAuthority> authorities;

    public AloutePrincipal(User user) {
        this.id = user.getId();
        this.tokensRevokedAt = user.getTokensRevokedAt();
        this.username = user.getUsername();
        this.password = user.getPassword();
        this.fullName = user.getFullName();
        this.avatarUrl = user.getAvatarUrl();
        this.active = user.isActive();
        this.authorities = List.of(new SimpleGrantedAuthority(user.getRole().getName()));
    }

    public Long getId() {
        return id;
    }

    public String getFullName() {
        return fullName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
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
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return active;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }
}
