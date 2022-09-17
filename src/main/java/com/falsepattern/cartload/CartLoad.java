package com.falsepattern.cartload;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.*;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;

import lombok.val;
import lombok.var;

import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.minecart.MinecartUpdateEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mod(modid = Tags.MODID,
     version = Tags.VERSION,
     name = Tags.MODNAME,
     acceptedMinecraftVersions = "[1.7.10]",
     acceptableRemoteVersions = "*")
public class CartLoad {
    public static final double SPEED_EPSILON = 0.0001;
    private static final Map<EntityMinecart, ForgeChunkManager.Ticket> cartTickets = new HashMap<>();
    private static CartLoad instance;
    private int cleanupCounter = 0;

    public CartLoad() {
        instance = this;
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
        ForgeChunkManager.setForcedChunkLoadingCallback(instance, (tickets, world) -> tickets.forEach((ticket) -> {
            if (ticket.getType() == ForgeChunkManager.Type.ENTITY) {
                val entity = ticket.getEntity();
                if (entity instanceof EntityMinecart) {
                    val minecart = (EntityMinecart) entity;
                    cartTickets.put(minecart, ticket);
                    processMinecart(minecart);
                }
            }
        }));
    }

    @SubscribeEvent
    public void onTick(TickEvent e) {
        if (e.side == Side.SERVER && e.phase == TickEvent.Phase.END) {
            cleanupCounter++;
            if (cleanupCounter < 100) {
                return;
            }
            cleanupCounter = 0;
            List<EntityMinecart> dead = null;
            for (val cart: cartTickets.keySet()) {
                if (!cart.isDead) {
                    continue;
                }
                if (dead == null) {
                    dead = new ArrayList<>();
                }
                dead.add(cart);
            }
            if (dead == null) {
                return;
            }
            for (val cart: dead) {
                ForgeChunkManager.releaseTicket(cartTickets.remove(cart));
            }
        }
    }

    @SubscribeEvent
    public void onMinecart(MinecartUpdateEvent e) {
        processMinecart(e.minecart);
    }

    private static void processMinecart(EntityMinecart minecart) {
        var ticket = cartTickets.get(minecart);
        double speed = Math.abs(minecart.motionX) + Math.abs(minecart.motionY) + Math.abs(minecart.motionZ);
        if (speed < SPEED_EPSILON) {
            if (ticket != null) {
                ForgeChunkManager.releaseTicket(ticket);
                cartTickets.remove(minecart);
            }
            return;
        }
        if (ticket == null) {
            ticket = ForgeChunkManager.requestTicket(instance, minecart.worldObj, ForgeChunkManager.Type.ENTITY);
            ticket.bindEntity(minecart);
            ticket.setChunkListDepth(2);
            cartTickets.put(minecart, ticket);
        }
        val CX = Math.floorDiv((int) minecart.posX, 16);
        val CZ = Math.floorDiv((int) minecart.posZ, 16);
        val req = ticket.getChunkList();
        val current = new ChunkCoordIntPair(CX, CZ);
        if (!req.contains(current)) {
            ForgeChunkManager.forceChunk(ticket, current);
        }
        ForgeChunkManager.reorderChunk(ticket, current);
        val cX = (minecart.posX % 16 + 16) % 16;
        val cZ = (minecart.posZ % 16 + 16) % 16;
        ChunkCoordIntPair next = null;
        if (cX < 2) {
            next = new ChunkCoordIntPair(CX - 1, CZ);
        } else if (cX > 14) {
            next = new ChunkCoordIntPair(CX + 1, CZ);
        } else if (cZ < 2) {
            next = new ChunkCoordIntPair(CX, CZ - 1);
        } else if (cZ > 14) {
            next = new ChunkCoordIntPair(CX, CZ + 1);
        }
        if (next == null) {
            return;
        }
        if (!req.contains(next)) {
            ForgeChunkManager.forceChunk(ticket, next);
        }
        ForgeChunkManager.reorderChunk(ticket, next);
    }
}